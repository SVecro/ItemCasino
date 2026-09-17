package com.itemcasino.core.chips;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChipsTest {

    private static final long POINT = 1_000_000L;

    @Test
    @DisplayName("one chip is one value point, and a diamond is 256 chips")
    void units() {
        assertEquals(25_600, Chips.centsForValue(256 * POINT));
        assertEquals(256 * POINT, Chips.valueOfCents(25_600));
        assertEquals(100, Chips.centsOfChips(1));
        assertEquals(0, Chips.centsForValue(9_999)); // less than a cent credits nothing
    }

    @Test
    @DisplayName("fractional multipliers pay to the cent: 10 chips at x1.26 is 12.60")
    void fractionalWins() {
        assertEquals(1_260, Chips.payout(1_000, 1_260_000L));
        assertEquals(750, Chips.payout(500, 3, 2));
        assertEquals(0, Chips.payout(1_000, 0));
        assertEquals(Long.MAX_VALUE, Chips.payout(Long.MAX_VALUE / 2, 97_000_000L));
    }

    @Test
    @DisplayName("deposit then withdraw never gains, whatever the value and the fee")
    void noRoundTripGain() {
        Random random = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            long unit = 1 + (long) (random.nextDouble() * 300 * POINT);
            int count = 1 + random.nextInt(64);
            int fee = random.nextInt(3) == 0 ? 0 : random.nextInt(50_000);
            long deposited = Chips.centsForValue(unit * count);
            long cost = Chips.withdrawCost(unit * count, fee);
            assertTrue(cost >= deposited, "unit " + unit + " x" + count + " fee " + fee);
            if (fee >= 20_000) {
                assertTrue(cost >= deposited * 102 / 100, "a 2% fee must cost at least 2%");
            }
        }
    }

    @Test
    @DisplayName("affordable() buys the most items the balance covers, and never one more")
    void affordable() {
        Random random = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            long unit = 1 + (long) (random.nextDouble() * 500 * POINT);
            long balance = (long) (random.nextDouble() * 5_000_000);
            int fee = 20_000;
            long n = Chips.affordable(balance, unit, fee);
            assertTrue(Chips.withdrawCost(unit * n, fee) <= balance);
            assertTrue(Chips.withdrawCost(unit * (n + 1), fee) > balance);
        }
    }

    @Test
    @DisplayName("balances format without trailing zeros and never wrap")
    void formattingAndSaturation() {
        assertEquals("12.6", Chips.format(1_260));
        assertEquals("12.05", Chips.format(1_205));
        assertEquals("7", Chips.format(700));
        assertEquals("0", Chips.format(-5));
        assertEquals(Long.MAX_VALUE, Chips.add(Long.MAX_VALUE - 1, 5));
        assertEquals(0, Chips.add(3, -10));
    }
}
