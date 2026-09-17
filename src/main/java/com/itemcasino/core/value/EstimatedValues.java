package com.itemcasino.core.value;

import java.util.Arrays;

/**
 * Splits the value table into what is <em>known</em> and what is merely <em>estimated</em>.
 *
 * <h2>The problem it solves</h2>
 * Anything the recipe graph cannot reach from an authored base value used to get a flat rarity
 * price — sixteen points for every common item. That put netherrack, cobbled deepslate, seeds and
 * rotten flesh above an iron ingot, and every one of them is infinite. A guess is dangerous in
 * <em>both</em> directions at an Upgrader: a guess that is too high makes a junk item a good wager,
 * and a guess that is too low makes a rare item a cheap target.
 *
 * <h2>The rule</h2>
 * <ol>
 *   <li><b>Known</b>: priced from base values through recipes alone. Usable everywhere.</li>
 *   <li><b>Estimated</b>: priced only because a guess was allowed in. First the guess goes to items
 *       nothing crafts (the true raw materials), and recipes carry it forward, so a block of nine
 *       estimated items is worth nine of them rather than one. Whatever is still unpriced after
 *       that (items that only craft into each other) takes the guess directly.</li>
 * </ol>
 * An estimated item may be <em>wagered</em> at its estimate, which is deliberately low: the only
 * person a low guess can cost is the player who chose to put that item on the table, knowing its
 * price. It may never be a <em>target</em>, so a low guess can never be farmed from the house.
 * Known values are never lowered by a path through an estimate.
 */
public final class EstimatedValues {

    /**
     * @param values    per item: the known value, else the estimate, else {@link Fixed#INF}
     * @param estimated per item: true when the value exists only thanks to a guess
     * @param solution  diagnostics (winning conversion per item, relaxation count, cycles)
     */
    public record Result(long[] values, boolean[] estimated, ValueSolution solution) {
        public int estimatedCount() {
            int n = 0;
            for (boolean e : estimated) if (e) n++;
            return n;
        }
    }

    private EstimatedValues() {}

    public static Result solve(ValueGraph graph, long[] seed, boolean[] pinned, long[] guess) {
        int n = graph.itemCount;
        ValueSolution known = Relaxer.solve(graph, seed, pinned);

        boolean[] crafted = new boolean[n];
        for (int c = 0; c < graph.conversionCount; c++) crafted[graph.outputOf(c)] = true;

        // Pass two: the guess enters only through raw materials, and recipes carry it forward.
        long[] rawSeed = Arrays.copyOf(seed, n);
        for (int i = 0; i < n; i++) {
            if (known.values()[i] == Fixed.INF && !crafted[i] && guess[i] != Fixed.INF) {
                rawSeed[i] = guess[i];
            }
        }
        ValueSolution throughRaw = Relaxer.solve(graph, rawSeed, pinned);

        // Pass three: whatever recipes still cannot reach (closed loops) takes the guess itself.
        long[] loopSeed = Arrays.copyOf(rawSeed, n);
        boolean anyLeft = false;
        for (int i = 0; i < n; i++) {
            if (throughRaw.values()[i] == Fixed.INF && guess[i] != Fixed.INF) {
                loopSeed[i] = guess[i];
                anyLeft = true;
            }
        }
        ValueSolution last = anyLeft ? Relaxer.solve(graph, loopSeed, pinned) : throughRaw;

        long[] values = new long[n];
        boolean[] estimated = new boolean[n];
        int[] via = new int[n];
        for (int i = 0; i < n; i++) {
            long k = known.values()[i];
            if (k != Fixed.INF) {
                values[i] = k;
                via[i] = known.via()[i];
            } else {
                values[i] = last.values()[i];
                via[i] = last.via()[i];
                estimated[i] = values[i] != Fixed.INF;
            }
        }
        ValueSolution merged = new ValueSolution(values, via,
                known.relaxations() + throughRaw.relaxations() + (anyLeft ? last.relaxations() : 0),
                known.capped() || throughRaw.capped() || last.capped(), known.cycles());
        return new Result(values, estimated, merged);
    }
}
