package com.itemcasino.core.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineFieldTest {

    private static final int EDGE = 30_000;
    private static final long NO_CAP = Long.MAX_VALUE;

    private static double ev(int mines, int k, long cap) {
        BigInteger[] s = MineField.survival(mines, k);
        BigDecimal p = new BigDecimal(s[0]).divide(new BigDecimal(s[1]), MathContext.DECIMAL64);
        return p.doubleValue() * MineField.multiplierPpm(mines, k, EDGE, cap) / 1e6;
    }

    @Test
    @DisplayName("cashing out after any number of tiles returns 97% on average, never more")
    void theEdgeIsTheSameForEveryStrategy() {
        for (int mines = 1; mines <= 24; mines++) {
            for (int k = 1; k <= MineField.safeTiles(mines); k++) {
                double ev = ev(mines, k, NO_CAP);
                assertTrue(ev <= 0.97 + 1e-12, mines + " mines, " + k + " tiles: " + ev);
                assertTrue(ev > 0.97 - 1e-5, mines + " mines, " + k + " tiles: " + ev);
            }
        }
    }

    @Test
    @DisplayName("a cap only ever lowers what the player can expect")
    void aCapFavoursTheHouse() {
        long cap = 250 * MineField.PPM;
        for (int mines = 1; mines <= 24; mines++) {
            for (int k = 1; k <= MineField.safeTiles(mines); k++) {
                assertTrue(MineField.multiplierPpm(mines, k, EDGE, cap) <= cap);
                assertTrue(ev(mines, k, cap) <= 0.97 + 1e-12);
            }
        }
    }

    @Test
    @DisplayName("the payout ceiling limits the stake by the first tile and stops the board before it is passed")
    void payoutCeiling() {
        long cap = 250 * MineField.PPM;
        long ceiling = 250_000L * 100;
        for (int mines = 1; mines <= 24; mines++) {
            long most = MineField.maxStakeCents(mines, EDGE, cap, ceiling);
            long first = MineField.multiplierPpm(mines, 1, EDGE, cap);
            assertTrue(most > 0, mines + " mines allow no stake");
            assertFalse(MineField.nextTileOverCeiling(mines, 0, EDGE, cap, most, ceiling),
                    mines + " mines: the largest stake cannot turn its first tile");
            assertTrue(MineField.nextTileOverCeiling(mines, 0, EDGE, cap, most + first, ceiling)
                    || com.itemcasino.core.chips.Chips.payout(most + first, first) <= ceiling,
                    mines + " mines: the stake limit is not the largest");
            // Walk a board at the largest stake: no step ever pays past the ceiling.
            for (int k = 0; k < MineField.safeTiles(mines); k++) {
                if (MineField.nextTileOverCeiling(mines, k, EDGE, cap, most, ceiling)) break;
                long pays = com.itemcasino.core.chips.Chips.payout(most, MineField.multiplierPpm(mines, k + 1, EDGE, cap));
                assertTrue(pays <= ceiling, mines + " mines, tile " + (k + 1) + " pays " + pays);
            }
        }
        // One mine: x1.0104 first (97 % of 25/24), so nearly a quarter of a million chips may go on the board.
        assertEquals(24_742_284L, MineField.maxStakeCents(1, EDGE, cap, ceiling));
        assertFalse(MineField.nextTileOverCeiling(3, 2, EDGE, cap, 0, ceiling), "items have no ceiling");
    }

    @Test
    @DisplayName("three mines: a light first step, a steady climb")
    void threeMinesAreGentle() {
        assertEquals(MineField.PPM, MineField.multiplierPpm(3, 0, EDGE, NO_CAP), "no tile is a refund");
        long first = MineField.multiplierPpm(3, 1, EDGE, NO_CAP);
        assertTrue(first > 1_100_000 && first < 1_150_000, "first tile " + first);
        long previous = first;
        for (int k = 2; k <= 22; k++) {
            long next = MineField.multiplierPpm(3, k, EDGE, NO_CAP);
            assertTrue(next > previous, "the multiplier must climb");
            previous = next;
        }
    }

    @Test
    @DisplayName("the board is finished when it is clear or the cap is reached")
    void finishing() {
        assertTrue(MineField.isFinished(24, 1, EDGE, NO_CAP));
        assertFalse(MineField.isFinished(3, 5, EDGE, NO_CAP));
        assertTrue(MineField.isFinished(12, 8, EDGE, 250 * MineField.PPM));
    }

    @Test
    @DisplayName("mines are placed uniformly, and exactly as many as asked for")
    void layoutIsUniform() {
        Random random = new Random(5);
        Roller roller = Roller.of(random);
        int[] hits = new int[MineField.TILES];
        int boards = 200_000;
        for (int b = 0; b < boards; b++) {
            int mask = MineField.layout(5, roller);
            assertEquals(5, Integer.bitCount(mask));
            for (int t = 0; t < MineField.TILES; t++) if (MineField.isMine(mask, t)) hits[t]++;
        }
        double expected = boards * 5.0 / 25.0;
        for (int h : hits) assertTrue(Math.abs(h - expected) < expected * 0.02, "tile hit " + h);
    }
}
