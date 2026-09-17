package com.itemcasino.core.value;

import java.util.List;

/**
 * The immutable result of a {@link Relaxer} run.
 *
 * @param values      value per dense item index, {@link Fixed#INF} where unreachable
 * @param via         conversion index that produced each value, or -1 when seeded / unreachable
 * @param relaxations number of successful improvements performed (a health metric)
 * @param capped      true if the solver hit its iteration guard, meaning the values are suspect
 * @param cycles      derivation cycles found after convergence (diagnostics only)
 */
public record ValueSolution(long[] values, int[] via, long relaxations, boolean capped,
                            List<int[]> cycles) {

    public long value(int item) { return values[item]; }

    public boolean isPriced(int item) { return values[item] != Fixed.INF; }

    public int unpricedCount() {
        int n = 0;
        for (long v : values) if (v == Fixed.INF) n++;
        return n;
    }
}
