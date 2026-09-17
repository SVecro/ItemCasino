package com.itemcasino.valuation;

import com.itemcasino.core.value.Fixed;
import com.itemcasino.core.value.ValueGraph;
import com.itemcasino.core.value.ValueSolution;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable, fully-resolved value table.
 *
 * <p>Published by {@link ValuationEngine} through an {@link java.util.concurrent.atomic.AtomicReference},
 * so reads from the server thread are lock-free. A live session holds a hard reference to the
 * snapshot it started with: a mid-spin {@code /reload} therefore cannot retroactively change the
 * odds of a wager that is already in flight.
 */
public final class ValuationSnapshot {

    public static final ValuationSnapshot EMPTY = new ValuationSnapshot(
            null, null, new Reference2IntOpenHashMap<>(), new Item[0], new long[0],
            new boolean[0], new boolean[0], 0L);

    private final ValueGraph graph;
    private final ValueSolution solution;
    private final Reference2IntMap<Item> index;
    private final Item[] items;
    private final long[] values;
    private final boolean[] targetable;
    /** Priced only by an estimate: may be wagered, never targeted. See {@code EstimatedValues}. */
    private final boolean[] estimated;
    private final long tableHash;

    ValuationSnapshot(ValueGraph graph, ValueSolution solution, Reference2IntMap<Item> index,
                      Item[] items, long[] values, boolean[] targetable, boolean[] estimated,
                      long tableHash) {
        this.graph = graph;
        this.solution = solution;
        this.index = index;
        this.items = items;
        this.values = values;
        this.targetable = targetable;
        this.estimated = estimated;
        this.tableHash = tableHash;
        this.index.defaultReturnValue(-1);
    }

    public boolean isReady() { return items.length > 0; }

    /** Value of one unit of this item in micro-units, or {@link Fixed#INF} if unpriced. */
    public long value(Item item) {
        int i = index.getInt(item);
        return i < 0 ? Fixed.INF : values[i];
    }

    public boolean isPriced(Item item) { return value(item) != Fixed.INF; }

    /**
     * True when the item may be chosen as an Upgrader target.
     *
     * <p>An estimated value never qualifies: a guess that is too low would make the item a cheap
     * prize, and the house cannot tell a too-low guess from a right one.
     */
    public boolean isTargetable(Item item) {
        int i = index.getInt(item);
        return i >= 0 && targetableAt(i);
    }

    /** True when this item's price is an estimate rather than something derived from base values. */
    public boolean isEstimated(Item item) {
        int i = index.getInt(item);
        return i >= 0 && estimated[i];
    }

    public int estimatedCount() {
        int n = 0;
        for (boolean e : estimated) if (e) n++;
        return n;
    }

    public int itemCount() { return items.length; }

    public Item itemAt(int index) { return items[index]; }

    public long valueAt(int index) { return values[index]; }

    public boolean targetableAt(int index) {
        return targetable[index] && !estimated[index] && values[index] != Fixed.INF;
    }

    public boolean estimatedAt(int index) { return estimated[index]; }

    /** Identifies this exact table; persisted with a session so stale freezes can be detected. */
    public long tableHash() { return tableHash; }

    public ValueSolution solution() { return solution; }

    public ValueGraph graph() { return graph; }

    /** The change currencies, most valuable first; worked out once per table rather than per payout. */
    @javax.annotation.Nullable private volatile List<Item> changeDenominations;

    /**
     * The items a sub-item remainder is paid in: the {@code itemcasino:change_currency} tag, priced,
     * most valuable first. Tags only change on a reload, and a reload builds a new snapshot.
     */
    public List<Item> changeDenominations() {
        List<Item> cached = changeDenominations;
        if (cached != null) return cached;
        List<Item> found = new ArrayList<>(8);
        for (int i = 0; i < items.length; i++) {
            if (values[i] != Fixed.INF && values[i] > 0
                    && ItemFilter.isTagged(items[i], com.itemcasino.registry.CasinoTags.CHANGE_CURRENCY)) {
                found.add(items[i]);
            }
        }
        found.sort(java.util.Comparator.comparingLong((Item item) -> value(item)).reversed());
        changeDenominations = List.copyOf(found);
        return changeDenominations;
    }

    /** Every item that may be offered as a target, for the advisory client table. */

    public List<Item> targetableItems() {
        List<Item> out = new ArrayList<>(items.length / 2);
        for (int i = 0; i < items.length; i++) {
            if (targetableAt(i)) out.add(items[i]);
        }
        return out;
    }

    public int unpricedCount() {
        int n = 0;
        for (long v : values) if (v == Fixed.INF) n++;
        return n;
    }
}
