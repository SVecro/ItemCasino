package com.itemcasino.core.value;

/**
 * The Upgrader odds formula, in integer parts-per-million so that the server's roll and the
 * client's label can never round differently.
 *
 * <pre>Odds = min(0.90, 0.90 * inputValue / targetValue), refused below 0.001</pre>
 *
 * <p><strong>Why the bottom is a refusal and not a clamp.</strong> It used to be a clamp: any
 * shot longer than one in a thousand was quietly <em>raised</em> to one in a thousand. That made
 * the house pay whenever the target was worth more than a thousand times the stake — one cobblestone
 * against a dragon egg returned fifty times its value on average. The ceiling is still a clamp
 * (surplus value buys nothing, which costs the player, never the house); the floor refuses.
 */
public final class Odds {

    public static final int PPM = 1_000_000;
    /** 0.1 %: the longest shot the table will take. Anything longer is {@link #ILLEGAL}. */
    public static final int MIN_PPM = 1_000;
    /** 90 % */
    public static final int MAX_PPM = 900_000;
    /** Sentinel: the wager is not legal at all (unpriced side, or a longer shot than {@link #MIN_PPM}). */
    public static final int ILLEGAL = 0;

    private Odds() {}

    public static int ppm(long inputValue, long targetValue) {
        if (targetValue <= 0 || targetValue == Fixed.INF) return ILLEGAL;
        if (inputValue <= 0 || inputValue == Fixed.INF) return ILLEGAL;

        // input >= target implies a ratio >= 1, which the clamp pins to the ceiling anyway.
        // Short-circuiting here also removes the only realistic overflow case.
        if (inputValue >= targetValue) return MAX_PPM;

        long ppm;
        if (Math.multiplyHigh((long) MAX_PPM, inputValue) == 0) {
            ppm = ((long) MAX_PPM * inputValue) / targetValue;
        } else {
            ppm = (long) Math.floor((double) MAX_PPM * ((double) inputValue / (double) targetValue));
        }
        // Refused, never raised: see the class comment.
        if (ppm < MIN_PPM) return ILLEGAL;
        return (int) Math.min(MAX_PPM, ppm);
    }

    /** Formats a ppm value as a percentage with two decimals, e.g. {@code 34210 -> "3.42"}. */
    public static String percent(int ppm) {
        long hundredths = Math.round(ppm / 100.0);
        return (hundredths / 100) + "." + String.format("%02d", hundredths % 100);
    }
}
