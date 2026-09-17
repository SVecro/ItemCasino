package com.itemcasino.core.value;

/**
 * Saturating fixed-point arithmetic for item values.
 *
 * <p>Values are {@code long}s in micro-units: {@link #ONE} micro-units is "one value point".
 * {@link #INF} is the sentinel for "unknown / unreachable" and is absorbing under every operation.
 *
 * <p>Why not {@code BigFraction} (ProjectE's choice)? Odds are clamped to [0.001, 0.90] and
 * quantised to parts-per-million before they are ever observed, so 1e-6 relative precision is three
 * orders of magnitude finer than anything a player can see. Longs keep the solver allocation-free.
 *
 * <p>This class has no Minecraft dependencies and is unit-tested standalone.
 */
public final class Fixed {

    /** One "value point" expressed in micro-units. */
    public static final long ONE = 1_000_000L;

    /** Unknown / unreachable. Absorbing: anything combined with INF is INF. */
    public static final long INF = Long.MAX_VALUE;

    /**
     * Hard floor for any derived value. Prevents a value-creating recipe cycle from collapsing a
     * whole subtree to zero, which would turn the affected items into free lottery tickets.
     */
    public static final long MIN_POSITIVE = 1L;

    private Fixed() {}

    /** Converts whole value points to micro-units, saturating. */
    public static long ofPoints(long points) {
        if (points >= Long.MAX_VALUE / ONE) return INF;
        if (points <= 0) return 0L;
        return points * ONE;
    }

    /** Saturating addition. Overflow and INF both yield INF. */
    public static long add(long a, long b) {
        if (a == INF || b == INF) return INF;
        long r = a + b;
        // both operands are non-negative here, so a wrap-around is always a negative result
        return r < 0 ? INF : r;
    }

    /** Saturating subtraction, floored at zero. INF stays INF. */
    public static long sub(long a, long b) {
        if (a == INF) return INF;
        if (b == INF) return 0L;
        return Math.max(0L, a - b);
    }

    /** Saturating multiplication by a non-negative count. */
    public static long mul(long a, long count) {
        if (a == INF) return INF;
        if (count <= 0) return 0L;
        if (a == 0) return 0L;
        if (a > INF / count) return INF;
        return a * count;
    }

    /**
     * Ceiling division used when a recipe's cost is spread over several output items.
     *
     * <p>Rounding <em>down</em> here would leak value on every split/recombine round trip
     * (1 log to 4 planks, 4 planks back to... nothing in vanilla, but modded packs close that loop),
     * so we always round up and clamp to {@link #MIN_POSITIVE}.
     */
    public static long divCeil(long total, int count) {
        if (total == INF) return INF;
        if (count <= 1) return Math.max(MIN_POSITIVE, total);
        return Math.max(MIN_POSITIVE, (total + count - 1) / count);
    }

    /** Multiplies by {@code numerator/denominator}, rounding down, saturating. */
    public static long scale(long a, long numerator, long denominator) {
        if (a == INF) return INF;
        if (numerator <= 0 || denominator <= 0) return 0L;
        long hi = Math.multiplyHigh(a, numerator);
        if (hi != 0) {
            // would overflow 64 bits: fall back to double, which is plenty at this magnitude
            double d = (double) a * (double) numerator / (double) denominator;
            return d >= (double) INF ? INF : (long) d;
        }
        return (a * numerator) / denominator;
    }

    /** Human-readable rendering, e.g. {@code 1234500} -> {@code "1.23"}. */
    public static String format(long micro) {
        if (micro == INF) return "unpriced";
        long whole = micro / ONE;
        long frac = (micro % ONE) / 10_000L; // two decimals
        return whole + "." + (frac < 10 ? "0" + frac : Long.toString(frac));
    }
}
