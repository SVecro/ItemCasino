package com.itemcasino.core.game;

import com.itemcasino.core.game.jackpot.JackpotOdds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JackpotOddsTest {

    private static final int RETURN = 800_000;   // a draw hands back 80% of what it is fed
    private static final int CAP = 50_000;       // and can never be likelier than 5%

    @Test
    @DisplayName("a draw returns the same fraction of the offering whatever the pot holds")
    void theEdgeDoesNotMoveWithThePot() {
        // The property the whole formula exists for: chance x pot == return x offered. A pot worth
        // a million must be no better a deal than a pot worth ten, or the vault becomes a bank
        // everyone empties the moment it is worth emptying.
        for (long pot : new long[] { 1_000L, 100_000L, 10_000_000L, 4_000_000_000L }) {
            long offered = pot / 1000;               // a tenth of a percent: well under the cap
            if (offered <= 0) continue;
            long chance = JackpotOdds.drawPpm(offered, pot, RETURN, CAP);
            double expectedWinnings = chance / (double) JackpotOdds.PPM * pot;
            double paidIn = offered;
            assertEquals(0.80, expectedWinnings / paidIn, 0.01,
                    "pot " + pot + " returned " + (expectedWinnings / paidIn));
        }
    }

    @Test
    @DisplayName("no single draw can ever be likely")
    void theCapAlwaysBinds() {
        assertEquals(CAP, JackpotOdds.drawPpm(1_000_000L, 1_000_000L, RETURN, CAP),
                "offering the whole pot still only buys the cap");
        assertEquals(CAP, JackpotOdds.drawPpm(9_999_999L, 100L, RETURN, CAP),
                "offering far more than the pot still only buys the cap");
        for (long offered = 1; offered < 2_000_000L; offered += 9_973) {
            int chance = JackpotOdds.drawPpm(offered, 2_000_000L, RETURN, CAP);
            assertTrue(chance <= CAP, "a draw at " + chance + "ppm broke the ceiling");
        }
    }

    @Test
    @DisplayName("feeding more never makes the draw worse")
    void moreOfferedIsNeverWorse() {
        Random random = new Random(5);
        for (int trial = 0; trial < 20_000; trial++) {
            long pot = 1 + random.nextInt(50_000_000);
            long a = 1 + random.nextInt(1_000_000);
            long b = a + random.nextInt(1_000_000);
            assertTrue(JackpotOdds.drawPpm(b, pot, RETURN, CAP)
                            >= JackpotOdds.drawPpm(a, pot, RETURN, CAP),
                    "offering " + b + " was worse than offering " + a + " into " + pot);
        }
    }

    @Test
    @DisplayName("nonsense in, nothing out — and nothing overflows on the way")
    void degenerateInputsAreRefusedRatherThanWrapped() {
        assertEquals(0, JackpotOdds.drawPpm(0, 1000, RETURN, CAP));
        assertEquals(0, JackpotOdds.drawPpm(-5, 1000, RETURN, CAP));
        assertEquals(0, JackpotOdds.drawPpm(10, 0, RETURN, CAP));
        assertEquals(0, JackpotOdds.drawPpm(10, 1000, 0, CAP));

        // A pot valued near the top of a long must not wrap into a certainty.
        int huge = JackpotOdds.drawPpm(Long.MAX_VALUE / 4, Long.MAX_VALUE / 2, RETURN, CAP);
        assertTrue(huge >= 0 && huge <= CAP, "huge values produced " + huge + "ppm");
    }
}
