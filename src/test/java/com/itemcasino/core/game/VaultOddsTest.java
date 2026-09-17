package com.itemcasino.core.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultOddsTest {

    private static final int RETURN = 800_000;
    private static final int CAP = 500_000;

    @Test
    @DisplayName("whatever share is asked for, a draw returns the same fraction of the offering")
    void theEdgeDoesNotMoveWithTheShare() {
        long pot = 1_000_000_000L;
        long offered = 100_000L;
        for (int share : new int[] { 1, 5, 10, 25, 50, 100 }) {
            long prize = pot / 100 * share;
            int chance = VaultOdds.drawPpm(offered, prize, RETURN, CAP);
            double ev = chance / 1e6 * prize / offered;
            assertEquals(0.80, ev, 0.001, "share " + share + " returned " + ev);
        }
    }

    @Test
    @DisplayName("asking for less of the pot never makes the draw less likely")
    void smallerSharesAreLikelier() {
        Random random = new Random(3);
        for (int trial = 0; trial < 20_000; trial++) {
            long pot = 1 + random.nextInt(1_000_000_000);
            long offered = 1 + random.nextInt(10_000_000);
            int previous = Integer.MAX_VALUE;
            for (int share = 1; share <= 100; share++) {
                int chance = VaultOdds.drawPpm(offered, pot / 100 * share + 1, RETURN, CAP);
                assertTrue(chance <= previous, "share " + share + " is likelier than a smaller one");
                assertTrue(chance <= CAP);
                previous = chance;
            }
        }
    }

    @Test
    @DisplayName("no draw ever returns more than it should, cap or no cap")
    void neverOverpays() {
        Random random = new Random(11);
        for (int trial = 0; trial < 200_000; trial++) {
            long prize = 1 + random.nextInt(1_000_000_000);
            long offered = 1 + random.nextInt(1_000_000_000);
            int chance = VaultOdds.drawPpm(offered, prize, RETURN, CAP);
            assertTrue(chance / 1e6 * prize <= 0.8 * offered + 1e-6, "overpaid");
        }
    }

    @Test
    @DisplayName("the useful offering is exactly where the ceiling starts to bind")
    void usefulOfferingMeetsTheCeiling() {
        long pot = 10_000_000L;
        for (int share : new int[] { 1, 10, 50, 100 }) {
            long useful = VaultOdds.maxUsefulOffer(pot, share, RETURN, CAP);
            if (useful == Long.MAX_VALUE) continue;
            long prizeAt = (pot + useful) / 100 * share;
            assertEquals(CAP, VaultOdds.drawPpm(useful, prizeAt, RETURN, CAP), 5_000,
                    "share " + share + " at its useful offering");
            long below = useful / 2;
            long prizeBelow = (pot + below) / 100 * share;
            assertTrue(VaultOdds.drawPpm(below, prizeBelow, RETURN, CAP) < CAP);
        }
        // With a 90% ceiling and an 80% return the whole pot can never reach the ceiling.
        assertEquals(Long.MAX_VALUE, VaultOdds.maxUsefulOffer(pot, 100, RETURN, 900_000));
    }

    @Test
    @DisplayName("shares round down, never up, and never overflow")
    void sharesRoundDown() {
        assertEquals(0, VaultOdds.share(1, 50));
        assertEquals(1, VaultOdds.share(3, 50));
        assertEquals(64, VaultOdds.share(64, 100));
        assertEquals(Long.MAX_VALUE / 100 * 7, VaultOdds.share(Long.MAX_VALUE, 7));
        assertEquals(1, VaultOdds.clampShare(-5));
        assertEquals(100, VaultOdds.clampShare(500));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("with half the offering banked, the ceiling binds where the odds actually reach it")
    void halfBankedCeiling() {
        long pot = 1_000_000L;
        int half = 500_000;
        for (int share : new int[] { 10, 50, 100 }) {
            long useful = VaultOdds.maxUsefulOffer(pot, share, RETURN, CAP, half);
            if (useful == Long.MAX_VALUE) continue;
            long below = useful - Math.max(1, useful / 100);
            long priceAt = VaultOdds.share(pot + VaultOdds.potPart(useful, half), share);
            long priceBelow = VaultOdds.share(pot + VaultOdds.potPart(below, half), share);
            org.junit.jupiter.api.Assertions.assertTrue(VaultOdds.drawPpm(useful, priceAt, RETURN, CAP) >= CAP - 2,
                    share + "%: the ceiling is not reached at the useful offer");
            org.junit.jupiter.api.Assertions.assertTrue(VaultOdds.drawPpm(below, priceBelow, RETURN, CAP) < CAP,
                    share + "%: the ceiling was already reached below the useful offer");
        }
        org.junit.jupiter.api.Assertions.assertEquals(VaultOdds.maxUsefulOffer(pot, 50, RETURN, CAP),
                VaultOdds.maxUsefulOffer(pot, 50, RETURN, CAP, 1_000_000));
        org.junit.jupiter.api.Assertions.assertEquals(3, VaultOdds.potPart(7, half), "the house keeps the odd one");
        org.junit.jupiter.api.Assertions.assertEquals(0, VaultOdds.potPart(1, half));
        org.junit.jupiter.api.Assertions.assertEquals(Long.MAX_VALUE / 2, VaultOdds.potPart(Long.MAX_VALUE, half));
    }
}
