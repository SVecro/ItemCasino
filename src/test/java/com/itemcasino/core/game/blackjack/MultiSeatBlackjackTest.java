package com.itemcasino.core.game.blackjack;

import com.itemcasino.core.game.Roller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three chairs, one shoe, one dealer.
 *
 * <p>Most of these use an <em>ordered</em> shoe rather than a searched seed. Fisher-Yates walks
 * {@code i} down from the top and swaps with {@code roller.nextInt(i + 1)}; a roller that always
 * answers {@code bound - 1} therefore swaps every card with itself and leaves the shoe in build
 * order — ace, two, three… — so the cards each chair is dealt are readable from the test itself.
 * That is a property of the shuffle, not a seam cut into the engine for the tests.
 */
class MultiSeatBlackjackTest {

    /** A shoe in build order: the draws come out A, 2, 3, 4, 5, 6, 7, 8, 9, 10, J, Q, K, A… */
    private static BlackjackTable ordered(int seats) {
        return new BlackjackTable(Rules.DEFAULT, bound -> bound - 1, seats);
    }

    private static boolean[] all(int seats) {
        boolean[] playing = new boolean[seats];
        java.util.Arrays.fill(playing, true);
        return playing;
    }

    /** Deals seed after seed until one comes out the way the test needs. */
    private static BlackjackTable dealt(boolean[] playing, Predicate<BlackjackTable> wanted) {
        for (long seed = 0; seed < 500_000L; seed++) {
            BlackjackTable table =
                    new BlackjackTable(Rules.DEFAULT, Roller.of(new Random(seed)), playing.length);
            table.deal(playing);
            if (wanted.test(table)) return table;
        }
        throw new IllegalStateException("no seed produced the requested deal");
    }

    @Test
    @DisplayName("the cards go round the table, then the dealer, then round again")
    void dealsRoundTheTable() {
        BlackjackTable table = ordered(3);
        assertTrue(table.deal(all(3)));

        assertEquals(0, table.hand(0).get(0).rank());
        assertEquals(1, table.hand(1).get(0).rank());
        assertEquals(2, table.hand(2).get(0).rank());
        assertEquals(3, table.dealer().get(0).rank(), "the up-card comes after the first round");
        assertEquals(4, table.hand(0).get(1).rank());
        assertEquals(5, table.hand(1).get(1).rank());
        assertEquals(6, table.hand(2).get(1).rank());
        assertEquals(7, table.dealer().get(1).rank(), "the hole card comes last");

        assertEquals(BlackjackPhase.PLAYER_TURN, table.phase());
        assertEquals(0, table.turn());
        assertTrue(table.holeHidden());
        assertEquals(1, table.dealerVisible().size());
    }

    @Test
    @DisplayName("one seat at a time, and an empty chair is skipped")
    void onlyTheSeatOnTurnMayAct() {
        BlackjackTable table = ordered(3);
        assertTrue(table.deal(new boolean[] { true, false, true }));

        assertFalse(table.isPlaying(1));
        assertEquals(0, table.hand(1).size(), "a chair that sat out is dealt nothing");
        assertEquals(0, table.turn());

        assertFalse(table.apply(2, BlackjackAction.HIT), "seat 2 may not act on seat 0's turn");
        assertEquals(0, table.legalMask(2));
        assertEquals(0, table.hand(2).size() - 2, "and its hand is untouched");

        assertTrue(table.apply(0, BlackjackAction.STAND));
        assertEquals(2, table.turn(), "the turn steps over the empty chair");
        assertNull(table.settlement(1), "a chair that sat out settles nothing");
    }

    @Test
    @DisplayName("a natural is paid at once and sits out the turn order")
    void naturalSitsOut() {
        BlackjackTable table = dealt(all(3), t -> t.hand(0).isNatural()
                && !t.hand(1).isNatural() && !t.hand(2).isNatural()
                && t.phase() == BlackjackPhase.PLAYER_TURN);

        assertEquals(1, table.turn(), "the seat with the natural never gets a decision");
        assertEquals(0, table.legalMask(0));
        Settlement settled = table.settlement(0);
        assertNotNull(settled);
        assertEquals(Outcome.PLAYER_BLACKJACK, settled.outcome());
        assertEquals(2.5, settled.multiplier(), 1e-9);
        assertNull(table.settlement(1), "the others are still playing");
    }

    @Test
    @DisplayName("a dealer natural settles the whole table before anyone acts")
    void dealerNaturalTakesEveryone() {
        BlackjackTable table = dealt(all(3),
                t -> t.phase() == BlackjackPhase.SETTLED && t.dealer().isNatural());

        assertEquals(-1, table.turn());
        assertFalse(table.holeHidden());
        assertEquals(2, table.dealer().size(), "the dealer had no reason to draw");
        for (int seat = 0; seat < 3; seat++) {
            Settlement settled = table.settlement(seat);
            assertNotNull(settled);
            assertEquals(table.hand(seat).isNatural()
                            ? Outcome.PUSH : Outcome.DEALER_BLACKJACK,
                    settled.outcome(), "seat " + seat);
        }
    }

    @Test
    @DisplayName("the seat on turn can be made to stand and play moves on")
    void forceStandMovesOn() {
        BlackjackTable table = ordered(3);
        table.deal(all(3));

        assertFalse(table.forceStand(2), "only the seat on turn can be stood");
        assertTrue(table.forceStand(0));
        assertEquals(1, table.turn());
        assertTrue(table.forceStand());
        assertEquals(2, table.turn());
        assertTrue(table.forceStand());
        assertEquals(BlackjackPhase.SETTLED, table.phase());
        assertEquals(-1, table.turn());
    }

    @Test
    @DisplayName("every seat settles against the same dealer, who draws only when someone stands")
    void everySeatSettlesAgainstOneDealer() {
        Random random = new Random(20260918L);
        for (int round = 0; round < 20_000; round++) {
            boolean[] playing = { true, round % 3 != 0, round % 5 != 0 };
            BlackjackTable table =
                    new BlackjackTable(Rules.DEFAULT, Roller.of(random), 3);
            assertTrue(table.deal(playing));

            int guard = 0;
            while (table.phase() == BlackjackPhase.PLAYER_TURN) {
                int seat = table.turn();
                assertTrue(seat >= 0 && playing[seat], "the turn is always a playing seat");
                for (int other = 0; other < 3; other++) {
                    if (other != seat) {
                        assertFalse(table.apply(other, BlackjackAction.HIT), "out of turn");
                    }
                }
                // A crude fixed strategy: it only has to exercise every branch, not win.
                table.apply(seat, table.hand(seat).total() < 17
                        ? BlackjackAction.HIT : BlackjackAction.STAND);
                assertTrue(++guard < 40, "a hand that never ends");
            }

            assertEquals(BlackjackPhase.SETTLED, table.phase());
            assertEquals(-1, table.turn());

            int dealerTotal = table.dealer().total();
            boolean everyoneBusted = true;
            for (int seat = 0; seat < 3; seat++) {
                Settlement settled = table.settlement(seat);
                if (!playing[seat]) {
                    assertNull(settled, "an empty chair settles nothing");
                    assertEquals(0, table.hand(seat).size());
                    continue;
                }
                assertNotNull(settled, "seat " + seat + " never settled");
                assertEquals(dealerTotal, settled.dealerTotal(),
                        "every seat is scored against the one dealer");
                assertEquals(table.hand(seat).total(), settled.playerTotal());
                if (settled.outcome() != Outcome.PLAYER_BUST) everyoneBusted = false;

                if (settled.outcome() == Outcome.WIN || settled.outcome() == Outcome.LOSS) {
                    boolean won = table.hand(seat).total() > dealerTotal;
                    assertEquals(won, settled.outcome() == Outcome.WIN, "seat " + seat);
                }
            }
            if (everyoneBusted) {
                assertEquals(2, table.dealer().size(),
                        "nobody was left standing, so the dealer had no reason to draw");
            }
        }
    }
}
