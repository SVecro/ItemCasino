package com.itemcasino.core.game;

import java.math.BigInteger;

/**
 * The coin of a duel, weighted by the stakes.
 *
 * <p>Two stakes are only ever roughly equal: whole items and a tolerance make five iron ingots
 * against a block of iron a legal duel. With an even coin the smaller stake expects to win more than
 * it put up, by up to 5.6 % at a 10 % tolerance. Weighting the coin by the stakes removes that: a
 * seat wins with probability {@code its stake / both stakes}, so each player's expected return is
 * exactly what they staked, whatever the two amounts are.
 */
public final class DuelOdds {

    public static final int PPM = 1_000_000;

    private DuelOdds() {}

    /**
     * Seat A's chance of taking the pot, in parts per million, rounded to the nearest ppm.
     *
     * @return {@code a / (a + b)} in ppm; an even coin when either stake is not a positive amount
     */
    public static int seatAChancePpm(long a, long b) {
        if (a <= 0 || b <= 0) return PPM / 2;
        BigInteger total = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
        BigInteger scaled = BigInteger.valueOf(a).multiply(BigInteger.valueOf(PPM))
                .add(total.shiftRight(1));
        long ppm = scaled.divide(total).longValue();
        // Neither side is ever certain: a stake so small it rounds to nothing still has its ppm.
        return (int) Math.max(1, Math.min(PPM - 1, ppm));
    }

    /** Which seat a roll in {@code [0, PPM)} hands the pot to: seat A below its chance, seat B above. */
    public static int winner(int roll, int seatAChancePpm) {
        return roll < seatAChancePpm ? 0 : 1;
    }
}
