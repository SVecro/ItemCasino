package com.itemcasino.core.game;

import com.itemcasino.core.game.slots.SlotMachine;
import com.itemcasino.core.game.slots.SlotOutcome;
import com.itemcasino.core.game.slots.SlotSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotMachineTest {

    @Test
    @DisplayName("the paytable returns exactly 90%, counted over every position on the strips")
    void theEdgeIsWhereWeSaidItWas() {
        long[] ratio = SlotMachine.exactReturn();
        long paid = ratio[0];
        long total = ratio[1];

        // Enumerated, not sampled: 64^3 is small enough to count, so this is the machine's real
        // return rather than an estimate of it. A paytable edit that moves the edge fails here.
        assertEquals(262_144L, total, "the sample space is the strip weight cubed");
        double rtp = paid / (double) total;
        assertTrue(Math.abs(rtp - 0.90) < 0.0005,
                "return to player is " + rtp + ", expected 0.90 (a 10% house edge)");
    }

    @Test
    @DisplayName("the strip hands out every symbol, in its stated proportion")
    void theStripIsWeightedAsDeclared() {
        Map<SlotSymbol, Integer> counted = new EnumMap<>(SlotSymbol.class);
        for (int position = 0; position < SlotSymbol.STRIP_WEIGHT; position++) {
            counted.merge(SlotSymbol.atStripPosition(position), 1, Integer::sum);
        }
        for (SlotSymbol symbol : SlotSymbol.values()) {
            assertEquals(symbol.weight(), counted.getOrDefault(symbol, 0),
                    symbol + " occupies the wrong number of strip positions");
        }
    }

    @Test
    @DisplayName("three of a kind, two of a kind, and nothing are told apart")
    void combinationsAreReadCorrectly() {
        SlotOutcome triple = SlotMachine.evaluate(ordinal(SlotSymbol.GOLD),
                ordinal(SlotSymbol.GOLD), ordinal(SlotSymbol.GOLD));
        assertEquals(SlotOutcome.Kind.TRIPLE, triple.kind());
        assertEquals(SlotSymbol.GOLD.tripleMultiplier(), triple.multiplier());

        // A pair counts wherever it falls, including split across the two outer reels.
        for (int[] places : new int[][] { {0, 1, 2}, {0, 2, 1}, {2, 0, 1} }) {
            int[] faces = new int[3];
            faces[places[0]] = ordinal(SlotSymbol.IRON);
            faces[places[1]] = ordinal(SlotSymbol.IRON);
            faces[places[2]] = ordinal(SlotSymbol.GOLD);
            SlotOutcome pair = SlotMachine.evaluate(faces[0], faces[1], faces[2]);
            assertEquals(SlotOutcome.Kind.PAIR, pair.kind(), "a pair at " + places[0] + "/" + places[1]);
            assertEquals(SlotSymbol.IRON, pair.paying());
            assertEquals(SlotSymbol.IRON.pairMultiplier(), pair.multiplier());
        }

        SlotOutcome nothing = SlotMachine.evaluate(ordinal(SlotSymbol.COAL),
                ordinal(SlotSymbol.IRON), ordinal(SlotSymbol.GOLD));
        assertEquals(SlotOutcome.Kind.NOTHING, nothing.kind());
        assertEquals(0, nothing.multiplier());
    }

    @Test
    @DisplayName("losing is the normal outcome, and a low pair is a near miss rather than a win")
    void themachineMostlyTakesTheWager() {
        // A slot machine where most spins return something has nothing at stake. This pins the
        // shape of the table, not its numbers: the great majority of the sample space must pay
        // nothing at all.
        long losing = 0;
        long total = 0;
        for (SlotSymbol a : SlotSymbol.values()) {
            for (SlotSymbol b : SlotSymbol.values()) {
                for (SlotSymbol c : SlotSymbol.values()) {
                    long weight = (long) a.weight() * b.weight() * c.weight();
                    total += weight;
                    if (SlotMachine.evaluate(a.ordinal(), b.ordinal(), c.ordinal())
                            .multiplier() == 0) {
                        losing += weight;
                    }
                }
            }
        }
        double lossRate = losing / (double) total;
        assertTrue(lossRate > 0.70 && lossRate < 0.90,
                "spins lose " + lossRate + " of the time, which is not a slot machine");

        // And a pair that pays nothing must say so rather than announcing a match it will not honour.
        SlotOutcome coalPair = SlotMachine.evaluate(ordinal(SlotSymbol.COAL),
                ordinal(SlotSymbol.COAL), ordinal(SlotSymbol.IRON));
        assertEquals(0, SlotSymbol.COAL.pairMultiplier());
        assertEquals(SlotOutcome.Kind.NOTHING, coalPair.kind());
        assertTrue(!coalPair.paysAnything());
    }

    @Test
    @DisplayName("the ladder never inverts: a rarer symbol is never worth less")
    void theLadderIsMonotone() {
        SlotSymbol[] symbols = SlotSymbol.values();
        for (int i = 1; i < symbols.length; i++) {
            assertTrue(symbols[i].weight() <= symbols[i - 1].weight(),
                    symbols[i] + " is not rarer than " + symbols[i - 1]);
            assertTrue(symbols[i].tripleMultiplier() > symbols[i - 1].tripleMultiplier(),
                    symbols[i] + " pays no more than " + symbols[i - 1] + " for three");
            assertTrue(symbols[i].pairMultiplier() >= symbols[i - 1].pairMultiplier(),
                    symbols[i] + " pays less than " + symbols[i - 1] + " for two");
            assertTrue(symbols[i].pairMultiplier() == 0
                            || symbols[i].tripleMultiplier() > symbols[i].pairMultiplier(),
                    "three " + symbols[i] + " must beat two of them");
        }
    }

    @Test
    @DisplayName("a spin only ever produces faces the strip contains")
    void spinsStayOnTheStrip() {
        Random random = new Random(3);
        Roller roller = random::nextInt;
        Map<SlotSymbol, Integer> seen = new EnumMap<>(SlotSymbol.class);
        for (int trial = 0; trial < 500_000; trial++) {
            SlotOutcome outcome = SlotMachine.spin(roller);
            for (int face : new int[] { outcome.left(), outcome.middle(), outcome.right() }) {
                assertTrue(face >= 0 && face < SlotSymbol.values().length, "face off the strip");
                seen.merge(SlotSymbol.byId(face), 1, Integer::sum);
            }
            assertEquals(SlotMachine.evaluate(outcome.left(), outcome.middle(), outcome.right()),
                    outcome, "a spin must read the same as re-evaluating its own faces");
            if (outcome.kind() != SlotOutcome.Kind.NOTHING) assertNotNull(outcome.paying());
        }
        // Every face turns up, including the one in a quarter of a million.
        for (SlotSymbol symbol : SlotSymbol.values()) {
            assertTrue(seen.getOrDefault(symbol, 0) > 0, symbol + " never appeared");
        }
    }

    @Test
    @DisplayName("the observed return matches the counted one")
    void simulationAgreesWithTheEnumeration() {
        Random random = new Random(19);
        Roller roller = random::nextInt;
        long paid = 0;
        int spins = 3_000_000;
        for (int trial = 0; trial < spins; trial++) {
            paid += SlotMachine.spin(roller).multiplier();
        }
        double observed = paid / (double) spins;
        long[] exact = SlotMachine.exactReturn();
        double expected = exact[0] / (double) exact[1];
        // Three million spins on a machine whose top prize is one in 262,144: the tail is fat, so
        // the tolerance is wide on purpose. It is here to catch a broken roller, not to measure.
        assertTrue(Math.abs(observed - expected) < 0.05,
                "simulated " + observed + " against a counted " + expected);
    }

    private static int ordinal(SlotSymbol symbol) { return symbol.ordinal(); }
}
