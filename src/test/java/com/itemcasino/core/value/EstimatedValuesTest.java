package com.itemcasino.core.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstimatedValuesTest {

    private static List<int[]> slots(int... items) {
        List<int[]> out = new ArrayList<>();
        for (int item : items) out.add(new int[] { item });
        return out;
    }

    @Test
    @DisplayName("a guess enters through raw materials and recipes carry it forward")
    void guessesFlowThroughRecipes() {
        ValueGraph.Builder b = new ValueGraph.Builder();
        int cobble = b.intern("cobble");
        int ice = b.intern("ice");            // nothing crafts it, no base value
        int packed = b.intern("packed_ice");  // nine ice
        int blue = b.intern("blue_ice");      // nine packed ice
        b.addConversion("packed", packed, 1, slots(ice, ice, ice, ice, ice, ice, ice, ice, ice), 0, 0);
        b.addConversion("blue", blue, 1, slots(packed, packed, packed, packed, packed, packed, packed, packed, packed), 0, 0);
        ValueGraph g = b.build();

        long[] seed = new long[g.itemCount];
        Arrays.fill(seed, Fixed.INF);
        seed[cobble] = Fixed.ofPoints(1);
        long[] guess = new long[g.itemCount];
        Arrays.fill(guess, Fixed.ofPoints(1));

        EstimatedValues.Result r = EstimatedValues.solve(g, seed, new boolean[g.itemCount], guess);
        assertFalse(r.estimated()[cobble]);
        assertTrue(r.estimated()[ice]);
        assertTrue(r.estimated()[packed]);
        assertEquals(Fixed.ofPoints(1), r.values()[ice]);
        assertEquals(Fixed.ofPoints(9), r.values()[packed], "a crafted block is not flattened to the guess");
        assertEquals(Fixed.ofPoints(81), r.values()[blue]);
    }

    @Test
    @DisplayName("a known value is never lowered by a path through a guess")
    void knownValuesAreNotUndercut() {
        ValueGraph.Builder b = new ValueGraph.Builder();
        int diamond = b.intern("diamond");
        int junk = b.intern("junk");          // raw, guessed at 1
        int gem = b.intern("gem");            // known: one diamond; also craftable from one junk
        b.addConversion("gem_from_diamond", gem, 1, slots(diamond), 0, 0);
        b.addConversion("gem_from_junk", gem, 1, slots(junk), 0, 0);
        ValueGraph g = b.build();

        long[] seed = new long[g.itemCount];
        Arrays.fill(seed, Fixed.INF);
        seed[diamond] = Fixed.ofPoints(256);
        long[] guess = new long[g.itemCount];
        Arrays.fill(guess, Fixed.ofPoints(1));

        EstimatedValues.Result r = EstimatedValues.solve(g, seed, new boolean[g.itemCount], guess);
        assertEquals(Fixed.ofPoints(256), r.values()[gem]);
        assertFalse(r.estimated()[gem], "a target priced from a known recipe must stay targetable");
    }

    @Test
    @DisplayName("a closed loop nobody can enter takes the guess directly")
    void loopsTakeTheGuess() {
        ValueGraph.Builder b = new ValueGraph.Builder();
        int bottle = b.intern("honey_bottle");
        int block = b.intern("honey_block");
        b.addConversion("block", block, 1, slots(bottle, bottle, bottle, bottle), 0, 0);
        b.addConversion("bottles", bottle, 4, slots(block), 0, 0);
        ValueGraph g = b.build();

        long[] seed = new long[g.itemCount];
        Arrays.fill(seed, Fixed.INF);
        long[] guess = new long[g.itemCount];
        Arrays.fill(guess, Fixed.ofPoints(2));

        EstimatedValues.Result r = EstimatedValues.solve(g, seed, new boolean[g.itemCount], guess);
        assertTrue(r.estimated()[bottle] && r.estimated()[block]);
        assertTrue(r.values()[bottle] <= Fixed.ofPoints(2) && r.values()[block] <= Fixed.ofPoints(8));
        assertEquals(2, r.estimatedCount());
    }

    @Test
    @DisplayName("an item with no guess and no path stays unpriced")
    void noGuessStaysUnpriced() {
        ValueGraph.Builder b = new ValueGraph.Builder();
        int lonely = b.intern("barrier");
        ValueGraph g = b.build();
        long[] seed = { Fixed.INF };
        long[] guess = { Fixed.INF };
        EstimatedValues.Result r = EstimatedValues.solve(g, seed, new boolean[1], guess);
        assertEquals(Fixed.INF, r.values()[lonely]);
        assertFalse(r.estimated()[lonely]);
    }
}
