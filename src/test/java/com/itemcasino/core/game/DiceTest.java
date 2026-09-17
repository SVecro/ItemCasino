package com.itemcasino.core.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceTest {

    private static final int EDGE = 30_000;

    @Test
    @DisplayName("exactly `chance` of the 10 000 rolls win, under and over alike")
    void theChanceIsTheChance() {
        for (int chance = 1; chance <= Dice.ROLLS; chance += 37) {
            for (boolean over : new boolean[] { false, true }) {
                int wins = 0;
                for (int roll = 0; roll < Dice.ROLLS; roll++) if (Dice.wins(roll, chance, over)) wins++;
                assertEquals(chance, wins, "chance " + chance + (over ? " over" : " under"));
            }
        }
    }

    @Test
    @DisplayName("every bet returns 97% on average, never more, and loses less than a ppm to rounding")
    void theEdgeIsTheSameForEveryBet() {
        for (int chance = 1; chance < Dice.ROLLS; chance++) {
            double ev = chance / (double) Dice.ROLLS * Dice.multiplierPpm(chance, EDGE) / 1e6;
            assertTrue(ev <= 0.97 + 1e-12, chance + ": " + ev);
            assertTrue(ev > 0.97 - 1e-6, chance + ": " + ev);
        }
    }

    @Test
    @DisplayName("the familiar numbers: 48.5% pays x2, 1% pays x97, 95% pays x1.0210")
    void familiarNumbers() {
        assertEquals(2_000_000L, Dice.multiplierPpm(4850, EDGE));
        assertEquals(97_000_000L, Dice.multiplierPpm(100, EDGE));
        assertEquals(1_021_052L, Dice.multiplierPpm(9500, EDGE));
        assertEquals(0L, Dice.multiplierPpm(0, EDGE));
    }

    @Test
    @DisplayName("the line and the chance convert both ways, and pack survives a round trip")
    void conversions() {
        for (int chance = 1; chance <= Dice.ROLLS; chance += 101) {
            for (boolean over : new boolean[] { false, true }) {
                assertEquals(chance, Dice.chanceAt(Dice.line(chance, over), over));
                int packed = Dice.pack(chance, over);
                assertEquals(chance, Dice.chanceOf(packed));
                assertEquals(over, Dice.isOver(packed));
            }
        }
        assertFalse(Dice.wins(-1, 5000, false));
        assertFalse(Dice.wins(Dice.ROLLS, 5000, true));
    }

    @Test
    @DisplayName("the stake ceiling keeps the whole win under the item limit")
    void stakeCeiling() {
        for (int chance = 100; chance <= 9500; chance += 50) {
            long mult = Dice.multiplierPpm(chance, EDGE);
            int stake = Dice.maxStake(1024, mult);
            assertTrue(stake * mult / 1_000_000L <= 1024, "chance " + chance);
            assertTrue((stake + 1) * mult / 1_000_000L > 1024 - 97, "chance " + chance + " is needlessly tight");
        }
        assertEquals(10, Dice.maxStake(1024, 97_000_000L));
        assertEquals(512, Dice.maxStake(1024, 2_000_000L));
        assertEquals(100, Dice.clampChance(5, 100, 9500));
        assertEquals(9500, Dice.clampChance(9999, 100, 9500));
    }
}
