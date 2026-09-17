package com.itemcasino.core.game;

/**
 * Predict the Dice: the server rolls a number from 0.00 to 99.99, and the player has said in advance
 * whether it will come in under or over a line they chose.
 *
 * <h2>Units</h2>
 * A roll is an int in {@code [0, 10 000)} — hundredths, so 4937 is shown as 49.37. The player's bet
 * is a <em>chance</em> in the same hundredths ({@code 4950} = 49.50 %) and a direction:
 * <ul>
 *   <li>under: wins when {@code roll < chance}</li>
 *   <li>over: wins when {@code roll >= 10 000 − chance}</li>
 * </ul>
 * Either way exactly {@code chance} of the 10 000 rolls win, so the chance on the screen is the
 * chance, with nothing lost to an off-by-one at the line.
 *
 * <h2>The multiplier</h2>
 * {@code (1 − edge) / chance}, rounded down to the ppm. So every bet returns {@code 1 − edge} of the
 * stake on average — 97 % by default — whether it is a coin toss at 48.5 % for ×2 or a long shot at
 * 1 % for ×97. Rounding only ever takes from the player, and by less than one ppm of the stake.
 */
public final class Dice {

    public static final int ROLLS = 10_000;
    public static final long PPM = 1_000_000L;
    private static final int OVER_BIT = 1 << 16;
    private static final int CHANCE_MASK = OVER_BIT - 1;

    private Dice() {}

    public static boolean wins(int roll, int chance, boolean over) {
        if (roll < 0 || roll >= ROLLS || chance <= 0) return false;
        return over ? roll >= ROLLS - chance : roll < chance;
    }

    /** Where the line sits on the 0–100 track: the chance itself under, its mirror over. */
    public static int line(int chance, boolean over) {
        return over ? ROLLS - chance : chance;
    }

    /** The chance a line gives, read back from the track: the inverse of {@link #line}. */
    public static int chanceAt(int line, boolean over) {
        return over ? ROLLS - line : line;
    }

    /** @return the payout multiplier in ppm, or 0 for a chance that is not a bet */
    public static long multiplierPpm(int chance, int edgePpm) {
        if (chance <= 0 || chance > ROLLS) return 0;
        long keep = PPM - Math.max(0, Math.min(edgePpm, (int) PPM));
        return keep * ROLLS / chance;
    }

    public static int clampChance(int chance, int min, int max) {
        int lo = Math.max(1, Math.min(min, ROLLS));
        int hi = Math.max(lo, Math.min(max, ROLLS));
        return Math.max(lo, Math.min(hi, chance));
    }

    /**
     * The most items a bet may stake so that the whole win stays under {@code maxPayoutItems}. A ×97
     * shot on a full stack is six thousand items: the ceiling scales the stake down with the odds
     * instead of refusing big multipliers outright.
     */
    public static int maxStake(long maxPayoutItems, long multiplierPpm) {
        if (multiplierPpm <= 0) return 0;
        long stake = maxPayoutItems * PPM / multiplierPpm;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, stake));
    }

    /** The bet as one int for the menu's option slot: the chance, and the direction in bit 16. */
    public static int pack(int chance, boolean over) {
        return (chance & CHANCE_MASK) | (over ? OVER_BIT : 0);
    }

    public static int chanceOf(int packed) {
        return packed & CHANCE_MASK;
    }

    public static boolean isOver(int packed) {
        return (packed & OVER_BIT) != 0;
    }
}
