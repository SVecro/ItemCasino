package com.itemcasino.core.value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Solves the item value table by monotone relaxation to a least fixed point.
 *
 * <h2>Why not a topological sort?</h2>
 * The recipe graph is <em>not</em> acyclic. Vanilla alone contains {@code ingot -> block} and
 * {@code block -> 9 ingots}; packs add deconstruction tables and transmutations. A DFS with
 * memoisation over that graph is exactly how you get a {@code StackOverflowError}.
 *
 * <h2>Why not Dijkstra?</h2>
 * Tempting, but wrong. A conversion divides its cost across {@code outputCount} items, so an
 * output can be <em>cheaper</em> than its inputs (one log worth 8 gives four planks worth 2). That
 * is a value-reducing edge, which breaks Dijkstra's settle-once property. We therefore use the heap
 * purely as a <em>priority worklist</em> (an SPFA / Bellman-Ford hybrid) and never mark an item
 * final: a popped item may be re-relaxed later if its value improves again.
 *
 * <h2>Why it terminates</h2>
 * Every write to {@code values[i]} strictly decreases it, and {@link Fixed#divCeil} floors every
 * derived value at {@link Fixed#MIN_POSITIVE}. A strictly decreasing sequence bounded below over a
 * finite set terminates. Cycles need no special handling for correctness — only for reporting.
 */
public final class Relaxer {

    /** Safety valve: a pathological pack cannot spin the server forever. */
    public static final long DEFAULT_MAX_RELAXATIONS_PER_ITEM = 512L;

    private Relaxer() {}

    /**
     * @param graph  the conversion graph
     * @param seed   initial values, {@link Fixed#INF} where unseeded (length == graph.itemCount)
     * @param pinned items whose seed is authoritative and must never be lowered by a recipe
     */
    public static ValueSolution solve(ValueGraph graph, long[] seed, boolean[] pinned) {
        return solve(graph, seed, pinned, DEFAULT_MAX_RELAXATIONS_PER_ITEM);
    }

    public static ValueSolution solve(ValueGraph graph, long[] seed, boolean[] pinned,
                                      long maxRelaxationsPerItem) {
        int n = graph.itemCount;
        long[] values = Arrays.copyOf(seed, n);
        int[] via = new int[n];
        Arrays.fill(via, -1);

        LongHeap heap = new LongHeap(Math.max(64, n / 4));
        for (int i = 0; i < n; i++) {
            if (values[i] != Fixed.INF) heap.push(values[i], i);
        }

        long relaxations = 0;
        long budget = Math.multiplyHigh(maxRelaxationsPerItem, Math.max(1, n)) != 0
                ? Long.MAX_VALUE
                : maxRelaxationsPerItem * Math.max(1, n);
        boolean capped = false;

        while (!heap.isEmpty()) {
            long key = heap.peekKey();
            int item = heap.peekItem();
            heap.pop();
            if (key > values[item]) continue;              // stale entry, a better one was queued later

            for (int k = graph.usedInStart(item), ke = graph.usedInEnd(item); k < ke; k++) {
                int conv = graph.usedIn(k);
                int out = graph.outputOf(conv);
                if (pinned != null && pinned[out]) continue;

                long derived = graph.derivedValue(conv, values);
                if (derived == Fixed.INF || derived >= values[out]) continue;

                values[out] = derived;
                via[out] = conv;
                heap.push(derived, out);

                if (++relaxations > budget) { capped = true; break; }
            }
            if (capped) break;
        }

        List<int[]> cycles = capped ? List.of() : findDerivationCycles(graph, values, via);
        return new ValueSolution(values, via, relaxations, capped, cycles);
    }

    /**
     * Diagnostics only. Walks the "winning conversion" chains: item -> the cheapest option of each
     * slot of {@code via[item]}. A cycle here means two items derive their value from each other,
     * which is where value-creating recipe loops show up. Vanilla produces none.
     *
     * @return one {@code int[]} of item indices per distinct cycle found
     */
    public static List<int[]> findDerivationCycles(ValueGraph graph, long[] values, int[] via) {
        int n = graph.itemCount;
        byte[] colour = new byte[n];          // 0 = white, 1 = on stack, 2 = done
        int[] stack = new int[Math.max(16, Math.min(n, 4096))];
        List<int[]> cycles = new ArrayList<>();

        for (int start = 0; start < n; start++) {
            if (colour[start] != 0) continue;
            int depth = 0;
            int cur = start;
            while (true) {
                if (colour[cur] == 1) {                     // found a cycle: unwind to it
                    int from = depth - 1;
                    while (from >= 0 && stack[from] != cur) from--;
                    if (from >= 0) cycles.add(Arrays.copyOfRange(stack, from, depth));
                    break;
                }
                if (colour[cur] == 2) break;
                colour[cur] = 1;
                if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
                stack[depth++] = cur;

                int conv = via[cur];
                if (conv < 0) break;
                int next = cheapestParent(graph, values, conv);
                if (next < 0) break;
                cur = next;
            }
            for (int i = 0; i < depth; i++) colour[stack[i]] = 2;
        }
        return cycles;
    }

    /** The single most expensive contributing ingredient of a conversion, i.e. its "parent". */
    private static int cheapestParent(ValueGraph graph, long[] values, int conv) {
        int best = -1;
        long bestValue = -1L;
        for (int slot = graph.slotStart(conv), se = graph.slotEnd(conv); slot < se; slot++) {
            int slotBest = -1;
            long slotBestValue = Fixed.INF;
            for (int o = graph.optionStart(slot), oe = graph.optionEnd(slot); o < oe; o++) {
                int item = graph.option(o);
                long v = values[item];
                if (v < slotBestValue) { slotBestValue = v; slotBest = item; }
            }
            if (slotBest >= 0 && slotBestValue != Fixed.INF && slotBestValue > bestValue) {
                bestValue = slotBestValue;
                best = slotBest;
            }
        }
        return best;
    }
}
