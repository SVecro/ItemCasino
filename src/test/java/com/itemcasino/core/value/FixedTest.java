package com.itemcasino.core.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedTest {

    @Test
    @DisplayName("arithmetic saturates instead of wrapping")
    void saturates() {
        assertEquals(Fixed.INF, Fixed.add(Long.MAX_VALUE - 5, 10));
        assertEquals(Fixed.INF, Fixed.add(Fixed.INF, 1));
        assertEquals(Fixed.INF, Fixed.mul(Long.MAX_VALUE / 2, 3));
        assertEquals(0L, Fixed.mul(1000, 0));
        assertEquals(0L, Fixed.sub(5, 10), "subtraction floors at zero");
    }

    @Test
    @DisplayName("divCeil never rounds down, so a split/recombine loop cannot leak value")
    void divCeilRoundsUp() {
        assertEquals(3L, Fixed.divCeil(7, 3));
        assertEquals(3L, Fixed.divCeil(9, 3));
        assertEquals(Fixed.MIN_POSITIVE, Fixed.divCeil(0, 64));
        assertEquals(Fixed.INF, Fixed.divCeil(Fixed.INF, 4));
    }

    @Test
    @DisplayName("nine ingots are exactly one block, in both directions")
    void ingotBlockIdentity() {
        long ingot = Fixed.ofPoints(10);
        long block = Fixed.mul(ingot, 9);
        assertEquals(ingot, Fixed.divCeil(block, 9));
    }

    @Test
    void formatting() {
        assertEquals("1.23", Fixed.format(1_234_500L));
        assertEquals("unpriced", Fixed.format(Fixed.INF));
        assertTrue(Fixed.ofPoints(9) == 9_000_000L);
    }
}
