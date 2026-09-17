package com.itemcasino.core.game.blackjack;

import com.itemcasino.core.game.PayoutMath;
import com.itemcasino.core.game.Roller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackjackTest {

    private static Hand hand(int... ranks) {
        Hand h = new Hand();
        for (int rank : ranks) h.add(new Card(rank, 0));
        return h;
    }

    @Test
    @DisplayName("aces are demoted only as far as they need to be")
    void handTotals() {
        assertEquals(21, hand(0, 12).total());
        assertTrue(hand(0, 12).isNatural());
        assertEquals(12, hand(0, 0).total());
        assertEquals(21, hand(0, 0, 8).total());
        assertEquals(19, hand(0, 8, 8).total());
        assertFalse(hand(0, 8, 8).isSoft());
        assertTrue(hand(0, 5).isSoft(), "A+6 is a soft 17");
        assertFalse(hand(9, 6).isSoft(), "10+7 is a hard 17");
        assertTrue(hand(12, 11, 1).isBust());
        assertFalse(hand(6, 6, 6).isNatural(), "three sevens is 21 but not a natural");
    }

    @Test
    @DisplayName("a card survives the one-byte wire encoding")
    void cardPacking() {
        for (int rank = 0; rank < Card.RANKS; rank++) {
            for (int suit = 0; suit < Card.SUITS; suit++) {
                Card card = new Card(rank, suit);
                assertEquals(card, Card.unpack(card.pack()));
            }
        }
    }

    @Test
    @DisplayName("double and surrender are only legal on the first decision")
    void legalMask() {
        BlackjackTable table = fixture(new int[] { 4, 5, 3, 6 }, Rules.DEFAULT);
        table.deal();
        int opening = table.legalMask();
        assertTrue(BlackjackAction.HIT.isIn(opening));
        assertTrue(BlackjackAction.STAND.isIn(opening));
        assertTrue(BlackjackAction.DOUBLE.isIn(opening));
        assertTrue(BlackjackAction.SURRENDER.isIn(opening));

        table.apply(BlackjackAction.HIT);
        int later = table.legalMask();
        assertFalse(BlackjackAction.DOUBLE.isIn(later));
        assertFalse(BlackjackAction.SURRENDER.isIn(later));
        assertFalse(table.apply(BlackjackAction.SURRENDER), "an illegal action changes nothing");
        assertFalse(table.apply(null));
    }

    @Test
    @DisplayName("S17 stands on a soft 17 and H17 hits it")
    void dealerPolicy() {
        int[] order = { 9, 0, 11, 5, 2 };   // player 10+Q, dealer A+6, next card 3
        BlackjackTable s17 = fixture(order, new Rules(1, false, true, true, false));
        s17.deal();
        s17.apply(BlackjackAction.STAND);
        assertEquals(17, s17.settlement().dealerTotal());
        assertEquals(Outcome.WIN, s17.settlement().outcome());

        BlackjackTable h17 = fixture(order, new Rules(1, true, true, true, false));
        h17.deal();
        h17.apply(BlackjackAction.STAND);
        assertEquals(20, h17.settlement().dealerTotal());
        assertEquals(Outcome.PUSH, h17.settlement().outcome());
    }

    @Test
    @DisplayName("payout multipliers: 3:2 natural, 4x doubled win, half back on surrender")
    void payouts() {
        BlackjackTable natural = fixture(new int[] { 0, 5, 12, 6 }, Rules.DEFAULT);
        natural.deal();
        assertEquals(Outcome.PLAYER_BLACKJACK, natural.settlement().outcome());
        assertEquals(2.5, natural.settlement().multiplier());
        assertFalse(natural.holeHidden(), "the hole card is revealed at settlement");

        BlackjackTable push = fixture(new int[] { 0, 0, 12, 12 }, Rules.DEFAULT);
        push.deal();
        assertEquals(Outcome.PUSH, push.settlement().outcome());
        assertEquals(1.0, push.settlement().multiplier());

        BlackjackTable doubled = fixture(new int[] { 4, 5, 5, 6, 9 }, Rules.DEFAULT);
        doubled.deal();
        doubled.apply(BlackjackAction.DOUBLE);
        assertEquals(2, doubled.settlement().betUnits());
        assertEquals(4.0, doubled.settlement().multiplier());

        BlackjackTable surrender = fixture(new int[] { 4, 5, 3, 6 }, Rules.DEFAULT);
        surrender.deal();
        surrender.apply(BlackjackAction.SURRENDER);
        assertEquals(0.5, surrender.settlement().multiplier());
        assertEquals(1, surrender.settlement().betUnits(),
                "a surrender never forfeits a doubled stake");
    }

    @Test
    @DisplayName("300k hands hold every invariant and land on the textbook house edge")
    void soak() {
        Random random = new Random(20260913L);
        long wagered = 0, returned = 0, naturals = 0;
        int hands = 300_000;

        for (int i = 0; i < hands; i++) {
            BlackjackTable table = new BlackjackTable(Rules.DEFAULT, Roller.of(random));
            table.deal();
            int guard = 0;
            while (table.phase() == BlackjackPhase.PLAYER_TURN && guard++ < 20) {
                table.apply(table.player().total() < 17
                        ? BlackjackAction.HIT : BlackjackAction.STAND);
            }
            Settlement settlement = table.settlement();
            assertNotNull(settlement);
            assertEquals(BlackjackPhase.SETTLED, table.phase());
            assertFalse(table.holeHidden());
            assertTrue(table.betUnits() >= 1 && table.betUnits() <= 2);

            wagered += 1000L * settlement.betUnits();
            returned += 1000L * settlement.payNumerator() / settlement.payDenominator();
            if (settlement.outcome() == Outcome.PLAYER_BLACKJACK) naturals++;
        }

        double rtp = returned / (double) wagered;
        assertTrue(rtp > 0.88 && rtp < 1.00, "mimic-the-dealer RTP was " + rtp);
        assertTrue(Math.abs(naturals / (double) hands - 0.0483) < 0.004);
    }

    @Test
    @DisplayName("Fisher-Yates gives a uniform first card and a complete shoe")
    void shoe() {
        Random random = new Random(99);
        int trials = 260_000;
        int[] firstRank = new int[Card.RANKS];
        for (int i = 0; i < trials; i++) {
            firstRank[new Shoe(1, Roller.of(random)).draw().rank()]++;
        }
        double expected = trials / (double) Card.RANKS;
        for (int count : firstRank) {
            assertTrue(Math.abs(count - expected) / expected < 0.03);
        }

        Shoe six = new Shoe(6, Roller.of(random));
        assertEquals(312, six.size());
        int[] seen = new int[Card.RANKS];
        while (six.remaining() > 0) seen[six.draw().rank()]++;
        for (int count : seen) assertEquals(24, count);
    }

    @Test
    @DisplayName("a 3:2 payout on three items is seven and a half, not seven")
    void payoutMath() {
        long[] split = PayoutMath.split(3, 5, 2);
        assertEquals(7L, split[0]);
        assertEquals(1L, split[1]);
        assertEquals(2L, split[2]);
        assertEquals(7L, PayoutMath.applyRounding(split, PayoutMath.Rounding.DISCARD));
        assertEquals(8L, PayoutMath.applyRounding(split, PayoutMath.Rounding.NEAREST));
        assertEquals(8L, PayoutMath.applyRounding(split, PayoutMath.Rounding.UP));
        assertEquals(0L, PayoutMath.split(4, 0, 1)[0]);
        assertEquals(2L, PayoutMath.split(4, 1, 2)[0]);
    }

    /**
     * Finds a seed whose shuffle deals the requested ranks in order. Reject sampling is crude but
     * it keeps the fixtures readable and the rules engine free of a test-only injection seam.
     */
    private static BlackjackTable fixture(int[] ranks, Rules rules) {
        for (long seed = 0; seed < 5_000_000L; seed++) {
            Shoe probe = new Shoe(rules.decks(), Roller.of(new Random(seed)));
            boolean match = true;
            for (int rank : ranks) {
                if (probe.draw().rank() != rank) { match = false; break; }
            }
            if (match) return new BlackjackTable(rules, Roller.of(new Random(seed)));
        }
        throw new IllegalStateException("no seed produced the requested fixture");
    }
}
