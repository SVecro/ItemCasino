package com.itemcasino.core.value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, flattened view of "what can be made from what".
 *
 * <p>Stored as struct-of-arrays / CSR rather than as a graph of objects: a 50 000-recipe modpack
 * becomes a handful of primitive arrays (a few MB) instead of a million small objects, which keeps
 * the solver allocation-free and cache-friendly.
 *
 * <p>Layout:
 * <pre>
 *   conversion c  -> slots  [convSlotStart[c],   convSlotStart[c+1])
 *   slot       s  -> options[slotOptStart[s],    slotOptStart[s+1])
 *   item       i  -> conversions consuming it: usedIn[usedInStart[i] .. usedInStart[i+1])
 * </pre>
 *
 * <p>A "slot" is one consumed ingredient position; multiplicity is therefore already encoded
 * (a recipe eating three planks has three slots). A slot's "options" are the interchangeable items
 * that satisfy it (a tag ingredient); the cheapest option wins.
 *
 * <p>No Minecraft types: item identity is an opaque {@link Object} key interned by the builder.
 */
public final class ValueGraph {

    public final int itemCount;
    public final int conversionCount;

    private final Object[] itemKeys;
    private final Object[] convSources;

    final int[] convOutput;
    final int[] convOutCount;
    final long[] convSurcharge;
    final long[] convRemainder;
    final int[] convSlotStart;
    final int[] slotOptStart;
    final int[] optItems;
    final int[] convRemStart;
    final int[] remItems;
    final int[] usedInStart;
    final int[] usedIn;

    private ValueGraph(Object[] itemKeys, Object[] convSources, int[] convOutput, int[] convOutCount,
                       long[] convSurcharge, long[] convRemainder, int[] convSlotStart,
                       int[] slotOptStart, int[] optItems, int[] convRemStart, int[] remItems,
                       int[] usedInStart, int[] usedIn) {
        this.itemKeys = itemKeys;
        this.convSources = convSources;
        this.itemCount = itemKeys.length;
        this.conversionCount = convOutput.length;
        this.convOutput = convOutput;
        this.convOutCount = convOutCount;
        this.convSurcharge = convSurcharge;
        this.convRemainder = convRemainder;
        this.convSlotStart = convSlotStart;
        this.slotOptStart = slotOptStart;
        this.optItems = optItems;
        this.convRemStart = convRemStart;
        this.remItems = remItems;
        this.usedInStart = usedInStart;
        this.usedIn = usedIn;
    }

    public Object itemKey(int item) { return itemKeys[item]; }

    public Object conversionSource(int conversion) { return convSources[conversion]; }

    public int outputOf(int conversion) { return convOutput[conversion]; }

    public int outputCountOf(int conversion) { return convOutCount[conversion]; }

    public int slotStart(int conversion) { return convSlotStart[conversion]; }

    public int slotEnd(int conversion) { return convSlotStart[conversion + 1]; }

    public int optionStart(int slot) { return slotOptStart[slot]; }

    public int optionEnd(int slot) { return slotOptStart[slot + 1]; }

    public int option(int index) { return optItems[index]; }

    public int usedInStart(int item) { return usedInStart[item]; }

    public int usedInEnd(int item) { return usedInStart[item + 1]; }

    public int usedIn(int index) { return usedIn[index]; }

    /**
     * Cost of performing one conversion given the current value table, or {@link Fixed#INF} if any
     * slot is not yet satisfiable. Each slot contributes the cheapest of its options.
     */
    public long conversionCost(int conversion, long[] values) {
        long cost = convSurcharge[conversion];
        if (cost == Fixed.INF) return Fixed.INF;
        for (int slot = convSlotStart[conversion], end = convSlotStart[conversion + 1]; slot < end; slot++) {
            long best = Fixed.INF;
            for (int o = slotOptStart[slot], oe = slotOptStart[slot + 1]; o < oe; o++) {
                long v = values[optItems[o]];
                if (v < best) best = v;
            }
            if (best == Fixed.INF) return Fixed.INF;
            cost = Fixed.add(cost, best);
        }
        // Byproducts handed back by the recipe (buckets from cake, bottles from stew...).
        // Their value is looked up dynamically because it is usually itself derived; an item that
        // is still unpriced simply earns no credit, which errs on the side of over-pricing.
        long credit = convRemainder[conversion];
        for (int r = convRemStart[conversion], re = convRemStart[conversion + 1]; r < re; r++) {
            long v = values[remItems[r]];
            if (v != Fixed.INF) credit = Fixed.add(credit, v);
        }
        return Fixed.sub(cost, credit);
    }

    /** Per-item value that this conversion would imply, given the current table. */
    public long derivedValue(int conversion, long[] values) {
        long cost = conversionCost(conversion, values);
        if (cost == Fixed.INF) return Fixed.INF;
        return Fixed.divCeil(cost, convOutCount[conversion]);
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {

        private final Map<Object, Integer> index = new HashMap<>();
        private final List<Object> keys = new ArrayList<>();
        private final List<Object> sources = new ArrayList<>();

        private final IntBuf output = new IntBuf();
        private final IntBuf outCount = new IntBuf();
        private final LongBuf surcharge = new LongBuf();
        private final LongBuf remainder = new LongBuf();
        private final IntBuf slotStart = new IntBuf();   // one entry per conversion (+ sentinel at build)
        private final IntBuf optStart = new IntBuf();    // one entry per slot (+ sentinel at build)
        private final IntBuf opts = new IntBuf();
        private final IntBuf remStart = new IntBuf();   // one entry per conversion
        private final IntBuf rems = new IntBuf();

        private int skipped;

        /** Interns an opaque item key, returning its dense index. */
        public int intern(Object key) {
            Integer existing = index.get(key);
            if (existing != null) return existing;
            int id = keys.size();
            keys.add(key);
            index.put(key, id);
            return id;
        }

        public int itemCount() { return keys.size(); }

        public int skippedConversions() { return skipped; }

        /**
         * Registers one recipe.
         *
         * @param source           opaque identifier kept for diagnostics (a recipe key, typically)
         * @param outputItem       dense index of the produced item
         * @param outputCount      how many are produced (must be >= 1)
         * @param slotOptions      one entry per consumed slot; each entry lists the interchangeable
         *                         item indices that satisfy that slot (never empty)
         * @param flatSurcharge    extra cost that the ingredient list cannot express (fuel, ...)
         * @param remainderCredit  value handed back by the recipe (buckets, containers, ...)
         */
        public void addConversion(Object source, int outputItem, int outputCount,
                                  List<int[]> slotOptions, long flatSurcharge, long remainderCredit) {
            addConversion(source, outputItem, outputCount, slotOptions, flatSurcharge,
                    remainderCredit, EMPTY);
        }

        /**
         * @param remainderItems items the recipe hands back (crafting remainders / byproducts);
         *                       their current value is credited against the cost at solve time
         */
        public void addConversion(Object source, int outputItem, int outputCount,
                                  List<int[]> slotOptions, long flatSurcharge, long remainderCredit,
                                  int[] remainderItems) {
            if (outputCount < 1 || slotOptions.isEmpty()) { skipped++; return; }
            for (int[] o : slotOptions) {
                if (o == null || o.length == 0) { skipped++; return; }  // unsatisfiable slot
            }
            sources.add(source);
            output.add(outputItem);
            outCount.add(outputCount);
            surcharge.add(flatSurcharge);
            remainder.add(remainderCredit);
            slotStart.add(optStart.size);
            for (int[] o : slotOptions) {
                optStart.add(opts.size);
                for (int item : o) opts.add(item);
            }
            remStart.add(rems.size);
            if (remainderItems != null) for (int item : remainderItems) rems.add(item);
        }

        private static final int[] EMPTY = new int[0];

        public ValueGraph build() {
            int nItems = keys.size();
            int nConv = output.size;
            int nSlots = optStart.size;

            int[] convSlotStart = Arrays.copyOf(slotStart.a, nConv + 1);
            convSlotStart[nConv] = nSlots;
            int[] slotOptStart = Arrays.copyOf(optStart.a, nSlots + 1);
            slotOptStart[nSlots] = opts.size;
            int[] optItems = Arrays.copyOf(opts.a, opts.size);
            int[] convRemStart = Arrays.copyOf(remStart.a, nConv + 1);
            convRemStart[nConv] = rems.size;
            int[] remItems = Arrays.copyOf(rems.a, rems.size);

            // reverse index, deduplicated per (item, conversion) pair
            int[] counts = new int[nItems + 1];
            boolean[] seen = new boolean[nItems];
            int[] touched = new int[64];
            for (int c = 0; c < nConv; c++) {
                int nTouched = 0;
                for (int s = convSlotStart[c]; s < convSlotStart[c + 1]; s++) {
                    for (int o = slotOptStart[s]; o < slotOptStart[s + 1]; o++) {
                        int item = optItems[o];
                        if (!seen[item]) {
                            seen[item] = true;
                            if (nTouched == touched.length) touched = Arrays.copyOf(touched, nTouched * 2);
                            touched[nTouched++] = item;
                            counts[item]++;
                        }
                    }
                }
                for (int i = 0; i < nTouched; i++) seen[touched[i]] = false;
            }
            int[] usedInStart = new int[nItems + 1];
            int running = 0;
            for (int i = 0; i < nItems; i++) { usedInStart[i] = running; running += counts[i]; }
            usedInStart[nItems] = running;
            int[] cursor = Arrays.copyOf(usedInStart, nItems);
            int[] usedIn = new int[running];
            for (int c = 0; c < nConv; c++) {
                int nTouched = 0;
                for (int s = convSlotStart[c]; s < convSlotStart[c + 1]; s++) {
                    for (int o = slotOptStart[s]; o < slotOptStart[s + 1]; o++) {
                        int item = optItems[o];
                        if (!seen[item]) {
                            seen[item] = true;
                            if (nTouched == touched.length) touched = Arrays.copyOf(touched, nTouched * 2);
                            touched[nTouched++] = item;
                            usedIn[cursor[item]++] = c;
                        }
                    }
                }
                for (int i = 0; i < nTouched; i++) seen[touched[i]] = false;
            }

            return new ValueGraph(
                    keys.toArray(),
                    sources.toArray(),
                    Arrays.copyOf(output.a, nConv),
                    Arrays.copyOf(outCount.a, nConv),
                    Arrays.copyOf(surcharge.a, nConv),
                    Arrays.copyOf(remainder.a, nConv),
                    convSlotStart, slotOptStart, optItems, convRemStart, remItems,
                    usedInStart, usedIn);
        }

        private static final class IntBuf {
            int[] a = new int[256];
            int size;
            void add(int v) {
                if (size == a.length) a = Arrays.copyOf(a, size * 2);
                a[size++] = v;
            }
        }

        private static final class LongBuf {
            long[] a = new long[256];
            int size;
            void add(long v) {
                if (size == a.length) a = Arrays.copyOf(a, size * 2);
                a[size++] = v;
            }
        }
    }
}
