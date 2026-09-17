package com.itemcasino.core.game.blackjack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DealClockTest {

    private static int runUntilResult(DealClock clock) {
        int ticks = 0;
        while (!clock.resultShown() && ticks < 5_000) {
            clock.tick();
            ticks++;
        }
        return ticks;
    }

    @Test
    @DisplayName("the opening deal lands one card at a time, player then dealer, hole card last")
    void openingOrder() {
        DealClock clock = new DealClock();
        clock.newHand();
        clock.dealt(2, 2);
        int[] landedAt = new int[4];
        int t = 0;
        while (clock.placed() < 4 && t < 1000) {
            if (clock.tick()) landedAt[clock.placed() - 1] = t;
            t++;
        }
        for (int i = 1; i < 4; i++) {
            assertEquals(DealClock.DEAL_INTERVAL, landedAt[i] - landedAt[i - 1], "card " + i);
        }
        assertTrue(clock.age(false, 0, 0F) > clock.age(true, 0, 0F), "the player's first card comes first");
        assertTrue(clock.age(true, 0, 0F) > clock.age(false, 1, 0F));
        assertTrue(clock.age(false, 1, 0F) > clock.age(true, 1, 0F), "the hole card comes last");
    }

    @Test
    @DisplayName("nothing is announced until the last card and the hole card have settled")
    void resultWaits() {
        DealClock clock = new DealClock();
        clock.newHand();
        clock.dealt(2, 2);
        for (int i = 0; i < 200; i++) clock.tick();
        // Stand: the dealer turns the hole card and draws two.
        clock.dealt(2, 4);
        clock.revealHole();
        clock.resultKnown();
        assertFalse(clock.resultShown());
        boolean sawDrawBeforeFlipEnded = false;
        int ticks = 0;
        while (!clock.resultShown() && ticks < 1000) {
            if (clock.tick() && clock.holeRevealAge(0F) < DealClock.FLIP_TICKS) sawDrawBeforeFlipEnded = true;
            ticks++;
            if (clock.placed() < 6 || clock.holeRevealAge(0F) < DealClock.FLIP_TICKS) {
                assertFalse(clock.resultShown(), "result shown with cards still to come");
            }
        }
        assertFalse(sawDrawBeforeFlipEnded, "the dealer drew while the hole card was turning");
        assertTrue(clock.landed(true, 3), "the dealer's last draw is down");
    }

    @Test
    @DisplayName("a player's hits come before the dealer's draws, whatever the hand sizes")
    void hitsThenDraws() {
        DealClock clock = new DealClock();
        clock.newHand();
        clock.dealt(2, 2);
        for (int i = 0; i < 200; i++) clock.tick();
        clock.dealt(3, 2);
        for (int i = 0; i < 200; i++) clock.tick();
        clock.dealt(4, 2);
        for (int i = 0; i < 200; i++) clock.tick();
        assertTrue(clock.landed(false, 3));
        clock.dealt(4, 4);
        clock.revealHole();
        clock.resultKnown();
        runUntilResult(clock);
        assertTrue(clock.age(false, 3, 0F) > clock.age(true, 2, 0F), "a dealer draw landed before a player hit");
        assertTrue(clock.age(true, 2, 0F) > clock.age(true, 3, 0F));
    }

    @Test
    @DisplayName("the server's estimate of the reveal is exactly what a client takes")
    void serverEstimateMatches() {
        for (int dealerDraws = 0; dealerDraws <= 4; dealerDraws++) {
            DealClock clock = new DealClock();
            clock.newHand();
            clock.dealt(2, 2);
            for (int i = 0; i < 300; i++) clock.tick();
            clock.dealt(2, 2 + dealerDraws);
            clock.revealHole();
            clock.resultKnown();
            int taken = runUntilResult(clock);
            assertEquals(DealClock.revealTicks(2, 2, 2, 2 + dealerDraws), taken, dealerDraws + " draws");
        }
        // A natural: the whole deal and the flip in one go.
        DealClock natural = new DealClock();
        natural.newHand();
        natural.dealt(2, 2);
        natural.revealHole();
        natural.resultKnown();
        int taken = runUntilResult(natural);
        assertEquals(DealClock.revealTicks(0, 0, 2, 2), taken);
        assertTrue(taken >= 4 * DealClock.DEAL_INTERVAL + DealClock.FLIP_TICKS + DealClock.RESULT_PAUSE,
                "a natural was revealed in " + taken + " ticks");
    }
}
