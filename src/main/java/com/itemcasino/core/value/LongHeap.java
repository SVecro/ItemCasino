package com.itemcasino.core.value;

import java.util.Arrays;

/**
 * A minimal binary min-heap of {@code (long key, int item)} pairs with no boxing and no
 * external dependencies (the core module must compile without fastutil).
 *
 * <p>Used as a priority worklist by {@link Relaxer}. Stale entries are tolerated: the consumer
 * checks {@code key > currentValue[item]} and skips.
 */
public final class LongHeap {

    private long[] keys;
    private int[] items;
    private int size;

    public LongHeap(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        this.keys = new long[cap];
        this.items = new int[cap];
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    public void push(long key, int item) {
        if (size == keys.length) {
            int cap = keys.length + (keys.length >> 1);
            keys = Arrays.copyOf(keys, cap);
            items = Arrays.copyOf(items, cap);
        }
        int i = size++;
        keys[i] = key;
        items[i] = item;
        siftUp(i);
    }

    /** @return the key of the minimum entry; call {@link #peekItem()} before {@link #pop()}. */
    public long peekKey() { return keys[0]; }

    public int peekItem() { return items[0]; }

    public void pop() {
        int last = --size;
        keys[0] = keys[last];
        items[0] = items[last];
        if (size > 0) siftDown(0);
    }

    private void siftUp(int i) {
        long k = keys[i];
        int v = items[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (keys[parent] <= k) break;
            keys[i] = keys[parent];
            items[i] = items[parent];
            i = parent;
        }
        keys[i] = k;
        items[i] = v;
    }

    private void siftDown(int i) {
        long k = keys[i];
        int v = items[i];
        int half = size >>> 1;
        while (i < half) {
            int child = (i << 1) + 1;
            int right = child + 1;
            if (right < size && keys[right] < keys[child]) child = right;
            if (keys[child] >= k) break;
            keys[i] = keys[child];
            items[i] = items[child];
            i = child;
        }
        keys[i] = k;
        items[i] = v;
    }
}
