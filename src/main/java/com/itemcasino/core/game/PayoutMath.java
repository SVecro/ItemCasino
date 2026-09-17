package com.itemcasino.core.game;

/**
 * Turns a rational payout multiplier into a whole number of items plus a leftover fraction.
 *
 * <p>The naive {@code (int) (count * 2.5)} silently deletes value on odd counts — a three-diamond
 * natural should pay seven and a half diamonds, and players notice the missing half. This class
 * keeps the residual explicit so the caller can apply an honest rounding policy.
 */
public final class PayoutMath {

    private PayoutMath() {}

    /** How the sub-item remainder of a payout is handled. */
    public enum Rounding {
        /** The house keeps it. Simplest; must be documented in the tooltip. */
        DISCARD,
        /** Round to the nearest whole item, ties up. */
        NEAREST,
        /** Always round up — player-friendly, slightly negative house edge on small wagers. */
        UP,
        /** Pay the remainder in cheaper "change" items priced by the valuation engine. */
        CHANGE
    }

    /**
     * @param wagered   number of items wagered (never zero)
     * @param num       payout numerator, including the returned stake
     * @param den       payout denominator
     * @return {@code [wholeItems, residualNumerator, residualDenominator]}
     */
    public static long[] split(long wagered, int num, int den) {
        if (den <= 0) throw new IllegalArgumentException("den " + den);
        long total = Math.multiplyHigh(wagered, num) == 0 ? wagered * num : Long.MAX_VALUE;
        long whole = total / den;
        long residual = total % den;
        return new long[] { whole, residual, den };
    }

    /** Applies a rounding policy to the whole/residual pair, returning the item count to pay out. */
    public static long applyRounding(long[] split, Rounding rounding) {
        long whole = split[0];
        long residualNum = split[1];
        long residualDen = split[2];
        if (residualNum == 0) return whole;
        return switch (rounding) {
            case DISCARD, CHANGE -> whole;                       // CHANGE pays the rest separately
            case UP -> whole + 1;
            case NEAREST -> (residualNum * 2 >= residualDen) ? whole + 1 : whole;
        };
    }
}
