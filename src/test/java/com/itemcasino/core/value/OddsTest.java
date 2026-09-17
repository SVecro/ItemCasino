package com.itemcasino.core.value;

import com.itemcasino.core.game.wheel.WheelMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OddsTest {

    @Test
    @DisplayName("the ceiling clamps to 90%, and a shot longer than 0.1% is refused rather than raised")
    void clamps() {
        assertEquals(Odds.MAX_PPM, Odds.ppm(Fixed.ofPoints(100), Fixed.ofPoints(100)));
        assertEquals(Odds.MAX_PPM, Odds.ppm(Fixed.ofPoints(500), Fixed.ofPoints(100)));
        assertEquals(450_000, Odds.ppm(Fixed.ofPoints(50), Fixed.ofPoints(100)));
        assertEquals(Odds.MIN_PPM, Odds.ppm(Fixed.ofPoints(1), Fixed.ofPoints(900)));
        assertEquals(Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(1), Fixed.ofPoints(901)));
        assertEquals(Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(1), Fixed.ofPoints(1_000_000)));
    }

    @Test
    @DisplayName("no legal wager ever pays the house back more than 90% on average")
    void theHouseNeverPaysOverTheOdds() {
        long[] targets = {1, 7, 256, 900, 901, 12_000, 20_000, 50_000, 1_000_000};
        for (long target : targets) {
            for (long input = 1; input <= 2_000; input++) {
                int ppm = Odds.ppm(Fixed.ofPoints(input), Fixed.ofPoints(target));
                if (ppm == Odds.ILLEGAL) continue;
                // Expected return as a fraction of the stake: ppm * target / input.
                double ev = ppm / 1_000_000.0 * target / input;
                assertTrue(ev <= 0.9 + 1e-9,
                        "wager " + input + " on target " + target + " returns " + ev);
            }
        }
    }

    @Test
    @DisplayName("an unpriced side is not a zero-probability wager, it is an illegal one")
    void illegalWagers() {
        assertEquals(Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(10), Fixed.INF));
        assertEquals(Odds.ILLEGAL, Odds.ppm(Fixed.INF, Fixed.ofPoints(10)));
        assertEquals(Odds.ILLEGAL, Odds.ppm(Fixed.ofPoints(10), 0));
    }

    @Test
    @DisplayName("no overflow, and monotone in the wagered value")
    void monotoneAndOverflowFree() {
        int previous = -1;
        for (long input = 1; input <= 2_000_000; input += 7919) {
            int ppm = Odds.ppm(input, 2_000_000L);
            assertTrue(ppm >= previous, "odds must not go down as the wager goes up");
            assertTrue(ppm == Odds.ILLEGAL || (ppm >= Odds.MIN_PPM && ppm <= Odds.MAX_PPM));
            previous = ppm;
        }
        assertEquals(Odds.MAX_PPM, Odds.ppm(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4));
        assertTrue(Math.abs(Odds.ppm(Long.MAX_VALUE / 8, Long.MAX_VALUE / 4) - 450_000) < 1000);
    }

    @Test
    @DisplayName("the wheel always stops inside the arc matching the decided outcome")
    void wheelAgreesWithTheLedger() {
        Random random = new Random(7);
        for (int trial = 0; trial < 200_000; trial++) {
            int ppm = Odds.MIN_PPM + random.nextInt(Odds.MAX_PPM - Odds.MIN_PPM);
            boolean win = random.nextBoolean();
            float angle = WheelMath.stopAngle(win, ppm, random.nextFloat());
            assertTrue(angle >= 0 && angle < WheelMath.TAU);
            assertEquals(win, WheelMath.isWinningAngle(angle, ppm));
        }
    }

    @Test
    @DisplayName("what the pointer lands on is what the ledger paid, sign and all")
    void thePointerShowsTheDecision() {
        Random random = new Random(11);
        for (int trial = 0; trial < 200_000; trial++) {
            int ppm = Odds.MIN_PPM + random.nextInt(Odds.MAX_PPM - Odds.MIN_PPM);
            boolean win = random.nextBoolean();
            float stop = WheelMath.stopAngle(win, ppm, random.nextFloat());

            // The whole round trip the renderer makes: decide an angle, turn it into a rotation,
            // then read back the sector sitting under the fixed pointer. A dropped negation here
            // showed players a losing wedge on a paid win, which is exactly what happened.
            float shown = WheelMath.underPointer(WheelMath.drawRotation(stop));
            assertEquals(win, WheelMath.isWinningAngle(shown, ppm),
                    "ppm=" + ppm + " win=" + win + " stop=" + stop + " shown=" + shown);

            // And it must survive the whole-turn offsets the client adds for the look of the spin.
            float withSpins = stop + 8 * WheelMath.TAU;
            float shownAfterSpins = WheelMath.underPointer(WheelMath.drawRotation(withSpins));
            assertEquals(win, WheelMath.isWinningAngle(shownAfterSpins, ppm));
        }
    }
}
