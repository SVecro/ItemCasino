package com.itemcasino.core.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelOddsTest {

    @Test
    @DisplayName("equal stakes toss an even coin")
    void equalStakes() {
        assertEquals(500_000, DuelOdds.seatAChancePpm(4_000_000L, 4_000_000L));
        assertEquals(500_000, DuelOdds.seatAChancePpm(0L, 7L), "a missing stake falls back to even");
    }

    @Test
    @DisplayName("each seat expects back exactly what it staked, to the ppm, at any ratio")
    void everySeatBreaksEven() {
        long[][] pairs = { { 900_100, 1_000_000 }, { 1, 1_000_000_000_000L }, { 256_000_000, 240_000_000 },
                { Long.MAX_VALUE / 3, Long.MAX_VALUE / 3 + 12345 }, { 13, 12 } };
        for (long[] p : pairs) {
            long a = p[0], b = p[1];
            int chanceA = DuelOdds.seatAChancePpm(a, b);
            double pot = (double) a + (double) b;
            double returnA = chanceA / 1e6 * pot / a;
            double returnB = (1e6 - chanceA) / 1e6 * pot / b;
            // Rounding to the nearest ppm moves a return by at most half a ppm of the pot over the stake.
            double tolerance = 0.5e-6 * pot / Math.min(a, b) + 1e-12;
            if (chanceA > 1 && chanceA < 999_999) {
                assertEquals(1.0, returnA, tolerance, "seat A at " + a + " vs " + b);
                assertEquals(1.0, returnB, tolerance, "seat B at " + a + " vs " + b);
            }
            assertTrue(chanceA >= 1 && chanceA <= 999_999, "a seat was made certain: " + chanceA);
        }
    }

    @Test
    @DisplayName("the old 10 % tolerance no longer favours the smaller stake")
    void toleranceEdgeIsGone() {
        int chance = DuelOdds.seatAChancePpm(900_000, 1_000_000);
        assertEquals(473_684, chance);
        assertEquals(0, DuelOdds.winner(chance - 1, chance));
        assertEquals(1, DuelOdds.winner(chance, chance));
    }
}
