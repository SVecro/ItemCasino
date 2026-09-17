package com.itemcasino.core.chips;

import java.math.BigInteger;

/**
 * Casino chips: the arithmetic, with no Minecraft in it.
 *
 * <h2>Units</h2>
 * One chip is worth one value point — the same point {@code /casino value} prints — so a diamond is
 * 256 chips. Balances are kept in hundredths of a chip ("cents"), which is what lets a ×1.26 win on
 * ten chips pay 12.60 rather than 12, and what makes a chip table fairer than an item table for any
 * multiplier that does not land on a whole number of items.
 *
 * <p>Value points are micro-units in {@code Fixed} (one point = 1 000 000), so one cent is 10 000
 * micro-units. Every conversion rounds against the player by less than a cent.
 */
public final class Chips {

    /** Cents in one chip. */
    public static final long CENTS = 100L;
    /** Value micro-units in one cent. */
    public static final long MICRO_PER_CENT = 10_000L;
    public static final long PPM = 1_000_000L;

    private Chips() {}

    public static long centsOfChips(long chips) {
        if (chips <= 0) return 0;
        return chips >= Long.MAX_VALUE / CENTS ? Long.MAX_VALUE : chips * CENTS;
    }

    /** What a deposit of this value credits: rounded down to the cent. */
    public static long centsForValue(long valueMicro) {
        if (valueMicro <= 0) return 0;
        return valueMicro / MICRO_PER_CENT;
    }

    /** The value of a balance, in the valuation engine's micro-units. */
    public static long valueOfCents(long cents) {
        if (cents <= 0) return 0;
        return cents >= Long.MAX_VALUE / MICRO_PER_CENT ? Long.MAX_VALUE : cents * MICRO_PER_CENT;
    }

    /**
     * What a win pays: the stake times a multiplier in ppm, rounded down to the cent. Exact for any
     * stake and multiplier a table can produce.
     */
    public static long payout(long stakeCents, long multiplierPpm) {
        if (stakeCents <= 0 || multiplierPpm <= 0) return 0;
        return saturate(BigInteger.valueOf(stakeCents).multiply(BigInteger.valueOf(multiplierPpm))
                .divide(BigInteger.valueOf(PPM)));
    }

    /** The stake times {@code numerator / denominator}, rounded down: blackjack's 3:2 and friends. */
    public static long payout(long stakeCents, long numerator, long denominator) {
        if (stakeCents <= 0 || numerator <= 0 || denominator <= 0) return 0;
        return saturate(BigInteger.valueOf(stakeCents).multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator)));
    }

    /**
     * What buying items of this value costs at the cashier: the value plus the fee, rounded
     * <em>up</em> to the cent. Rounding up is what keeps deposit-then-withdraw from ever gaining.
     */
    public static long withdrawCost(long valueMicro, int feePpm) {
        if (valueMicro <= 0) return 0;
        BigInteger withFee = BigInteger.valueOf(valueMicro)
                .multiply(BigInteger.valueOf(PPM + Math.max(0, feePpm)));
        BigInteger per = BigInteger.valueOf(PPM * MICRO_PER_CENT);
        BigInteger[] qr = withFee.divideAndRemainder(per);
        BigInteger cents = qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
        return saturate(cents);
    }

    /** How many items of one unit value a balance can buy. */
    public static long affordable(long balanceCents, long unitValueMicro, int feePpm) {
        long unitCost = withdrawCost(unitValueMicro, feePpm);
        if (unitCost <= 0) return 0;
        long count = balanceCents / unitCost;
        // The cost of n items is rounded once, not n times, so n may afford one more than n * unit.
        while (withdrawCost(safeMul(unitValueMicro, count + 1), feePpm) <= balanceCents) count++;
        while (count > 0 && withdrawCost(safeMul(unitValueMicro, count), feePpm) > balanceCents) count--;
        return count;
    }

    /** Adds to a balance without wrapping. */
    public static long add(long cents, long more) {
        long sum = cents + more;
        if (((cents ^ sum) & (more ^ sum)) < 0) return more > 0 ? Long.MAX_VALUE : 0;
        return Math.max(0, sum);
    }

    /** "1234.56", "12.5", "7" — trailing zeros dropped; the caller localises the separator. */
    public static String format(long cents) {
        long c = Math.max(0, cents);
        long whole = c / CENTS;
        long frac = c % CENTS;
        if (frac == 0) return Long.toString(whole);
        if (frac % 10 == 0) return whole + "." + (frac / 10);
        return whole + "." + (frac < 10 ? "0" : "") + frac;
    }

    private static long safeMul(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return (high != 0 || low < 0) ? Long.MAX_VALUE : low;
    }

    private static long saturate(BigInteger value) {
        if (value.signum() <= 0) return 0;
        return value.bitLength() >= 63 ? Long.MAX_VALUE : value.longValue();
    }
}
