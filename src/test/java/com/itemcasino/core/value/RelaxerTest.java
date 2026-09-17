package com.itemcasino.core.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaxerTest {

    /** Small fluent helper so the fixtures read like recipes. */
    private static final class G {
        final ValueGraph.Builder b = new ValueGraph.Builder();
        ValueSolution solution;
        ValueGraph graph;

        int item(String name) { return b.intern(name); }

        void recipe(String source, int out, int count, int... ins) {
            List<int[]> slots = new ArrayList<>();
            for (int in : ins) slots.add(new int[] { in });
            b.addConversion(source, out, count, slots, 0L, 0L);
        }

        void recipeOptions(String source, int out, int count, int[][] slots) {
            b.addConversion(source, out, count, List.of(slots), 0L, 0L);
        }

        void recipeReturning(String source, int out, int count, int[] returned, int... ins) {
            List<int[]> slots = new ArrayList<>();
            for (int in : ins) slots.add(new int[] { in });
            b.addConversion(source, out, count, slots, 0L, 0L, returned);
        }

        void solve(int[] seeded, long[] values, int[] pinnedItems) {
            graph = b.build();
            long[] seed = new long[graph.itemCount];
            Arrays.fill(seed, Fixed.INF);
            boolean[] pinned = new boolean[graph.itemCount];
            for (int i = 0; i < seeded.length; i++) seed[seeded[i]] = values[i];
            if (pinnedItems != null) for (int p : pinnedItems) pinned[p] = true;
            solution = Relaxer.solve(graph, seed, pinned);
        }

        long v(int item) { return solution.value(item); }
    }

    @Test
    @DisplayName("a linear chain sums its ingredients")
    void linearChain() {
        G g = new G();
        int raw = g.item("raw_iron"), ingot = g.item("ingot"), stick = g.item("stick"),
            pick = g.item("pickaxe");
        g.recipe("smelt", ingot, 1, raw);
        g.recipe("pickaxe", pick, 1, ingot, ingot, ingot, stick, stick);
        g.solve(new int[] { raw, stick },
                new long[] { Fixed.ofPoints(10), Fixed.ofPoints(1) }, null);

        assertEquals(Fixed.ofPoints(10), g.v(ingot));
        assertEquals(Fixed.ofPoints(32), g.v(pick));
        assertTrue(g.solution.cycles().isEmpty());
    }

    @Test
    @DisplayName("a multi-output recipe divides, which is a value-reducing edge")
    void multiOutput() {
        G g = new G();
        int log = g.item("log"), planks = g.item("planks"), stick = g.item("stick");
        g.recipe("planks", planks, 4, log);
        g.recipe("sticks", stick, 4, planks, planks);
        g.solve(new int[] { log }, new long[] { Fixed.ofPoints(8) }, null);

        assertEquals(Fixed.ofPoints(2), g.v(planks));
        assertEquals(Fixed.ofPoints(1), g.v(stick));
    }

    @Test
    @DisplayName("a tag ingredient costs its cheapest member")
    void cheapestAlternative() {
        G g = new G();
        int oak = g.item("oak"), birch = g.item("birch"), planks = g.item("planks");
        g.recipeOptions("planks", planks, 4, new int[][] { { oak, birch } });
        g.solve(new int[] { oak, birch },
                new long[] { Fixed.ofPoints(20), Fixed.ofPoints(8) }, null);
        assertEquals(Fixed.ofPoints(2), g.v(planks));
    }

    @Test
    @DisplayName("the vanilla ingot <-> block 2-cycle reaches a stable fixed point")
    void ingotBlockCycleIsStable() {
        G g = new G();
        int ingot = g.item("ingot"), block = g.item("block");
        int[] nine = new int[9];
        Arrays.fill(nine, ingot);
        g.recipe("block", block, 1, nine);
        g.recipe("deconstruct", ingot, 9, block);
        g.solve(new int[] { ingot }, new long[] { Fixed.ofPoints(10) }, null);

        assertEquals(Fixed.ofPoints(90), g.v(block));
        assertEquals(Fixed.ofPoints(10), g.v(ingot), "the round trip must not drift");
        assertFalse(g.solution.capped());
    }

    @Test
    @DisplayName("a value-creating loop terminates at the floor and is reported, not crashed on")
    void profitableCycleTerminates() {
        G g = new G();
        int a = g.item("A"), b = g.item("B");
        g.recipe("a_to_2b", b, 2, a);
        g.recipe("b_to_a", a, 1, b);
        g.solve(new int[] { a }, new long[] { Fixed.ofPoints(1000) }, null);

        assertEquals(Fixed.MIN_POSITIVE, g.v(a));
        assertEquals(Fixed.MIN_POSITIVE, g.v(b));
        assertFalse(g.solution.capped(), "monotone decrease must terminate on its own");
        assertFalse(g.solution.cycles().isEmpty(), "the loop must be reported for diagnosis");
    }

    @Test
    @DisplayName("a pinned seed survives a cheaper recipe")
    void pinnedSeedWins() {
        G g = new G();
        int star = g.item("nether_star"), ingot = g.item("ingot");
        g.recipe("fake_star", star, 1, ingot);
        g.solve(new int[] { star, ingot },
                new long[] { Fixed.ofPoints(20000), Fixed.ofPoints(5) }, new int[] { star });
        assertEquals(Fixed.ofPoints(20000), g.v(star));
    }

    @Test
    @DisplayName("crafting remainders are credited from their derived value, not a guess")
    void craftingRemainders() {
        G g = new G();
        int ingot = g.item("ingot"), bucket = g.item("bucket"), milk = g.item("milk"),
            wheat = g.item("wheat"), cake = g.item("cake");
        g.recipe("bucket", bucket, 1, ingot, ingot, ingot);
        g.recipe("milk", milk, 1, bucket);
        g.recipeReturning("cake", cake, 1, new int[] { bucket, bucket, bucket },
                milk, milk, milk, wheat);
        g.solve(new int[] { ingot, wheat },
                new long[] { Fixed.ofPoints(4), Fixed.ofPoints(1) }, null);

        assertEquals(Fixed.ofPoints(12), g.v(bucket));
        assertEquals(Fixed.ofPoints(12), g.v(milk));
        assertEquals(Fixed.ofPoints(1), g.v(cake), "the three buckets come back");
    }

    @Test
    @DisplayName("20k items and 60k recipes solve well inside a tick's worth of background time")
    void scales() {
        int items = 20_000;
        ValueGraph.Builder b = new ValueGraph.Builder();
        for (int i = 0; i < items; i++) b.intern("item" + i);
        Random random = new Random(1234);
        for (int c = 0; c < 60_000; c++) {
            int slots = 1 + random.nextInt(4);
            List<int[]> in = new ArrayList<>(slots);
            for (int s = 0; s < slots; s++) in.add(new int[] { random.nextInt(items) });
            b.addConversion("r" + c, random.nextInt(items), 1 + random.nextInt(4), in, 0L, 0L);
        }
        ValueGraph graph = b.build();
        long[] seed = new long[graph.itemCount];
        Arrays.fill(seed, Fixed.INF);
        for (int i = 0; i < 500; i++) seed[random.nextInt(items)] = Fixed.ofPoints(1 + random.nextInt(100));

        long start = System.nanoTime();
        ValueSolution solution = Relaxer.solve(graph, seed, new boolean[graph.itemCount]);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(solution.capped());
        assertTrue(millis < 2000, "solved in " + millis + " ms");
    }
}
