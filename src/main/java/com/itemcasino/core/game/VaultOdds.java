package com.itemcasino.core.game;

/**
 * The Vault's odds when the player names the share of the pot they are playing for.
 *
 * <p>One property carries over unchanged from the whole-pot draw, and it is the reason the formula
 * has this shape:
 *
 * <pre>chance x prize = return x offered</pre>
 *
 * so a draw is worth the same fixed fraction of the offering (80 % by default) whatever the pot
 * holds <em>and whatever share is asked for</em>. Asking for less of the pot buys a likelier draw at
 * exactly the same price; asking for all of it buys the long shot. The only place the property
 * bends is the ceiling on a single draw, and bending there always favours the house.
 *
 * <p>{@code prize} is the value the player would actually receive — whole items, rounded down per
 * kind — not {@code share x pot}: a quarter of a pot holding one nether star is no nether star at
 * all, and the odds must be priced against what is really paid.
 */
public final class VaultOdds {

    public static final int PPM = 1_000_000;
    public static final int MIN_SHARE = 1;
    public static final int MAX_SHARE = 100;

    private VaultOdds() {}

    public static int clampShare(int percent) {
        return Math.max(MIN_SHARE, Math.min(MAX_SHARE, percent));
    }

    /** How many of {@code count} items a {@code percent} share hands over, rounded down. */
    public static long share(long count, int percent) {
        if (count <= 0 || percent <= 0) return 0;
        int p = clampShare(percent);
        if (Math.multiplyHigh(count, p) != 0 || count * p < 0) return count / MAX_SHARE * p;
        return count * p / MAX_SHARE;
    }

    /**
     * @param offered    what the offering is worth
     * @param prizeValue what winning would actually hand over
     * @return the chance in ppm, never above {@code maxPpm}, zero when either side is worthless
     */
    public static int drawPpm(long offered, long prizeValue, int returnPpm, int maxPpm) {
        if (offered <= 0 || prizeValue <= 0 || returnPpm <= 0 || maxPpm <= 0) return 0;
        double chance = (double) returnPpm * ((double) offered / (double) prizeValue);
        if (chance >= maxPpm) return maxPpm;
        // Rounded down: the house never rounds a chance in the player's favour.
        return (int) Math.max(0L, (long) Math.floor(chance));
    }

    /**
     * The offering past which the ceiling binds and further items buy nothing, for a pot worth
     * {@code pot} before the offering goes in.
     *
     * <p>The offering joins the pot before the draw, so the prize grows with it:
     * {@code chance = r·O / (f·(P + O))}. That rises towards {@code r/f} and reaches the ceiling
     * {@code c} only if {@code r > c·f}, at {@code O = c·f·P / (r − c·f)}. Below that ratio the
     * ceiling is out of reach and every item still buys something.
     *
     * @return {@link Long#MAX_VALUE} when no offering is too large
     */
    public static long maxUsefulOffer(long pot, int percent, int returnPpm, int maxPpm) {
        return maxUsefulOffer(pot, percent, returnPpm, maxPpm, 1_000_000);
    }

    /**
     * The same, when only {@code potSharePpm} of the offering goes into the pot:
     * {@code chance = r·O / (f·(P + k·O))} reaches the ceiling at {@code O = c·f·P / (r − c·f·k)}.
     */
    public static long maxUsefulOffer(long pot, int percent, int returnPpm, int maxPpm, int potSharePpm) {
        if (pot <= 0 || returnPpm <= 0 || maxPpm <= 0) return Long.MAX_VALUE;
        int p = clampShare(percent);
        double k = Math.max(0, Math.min(1_000_000, potSharePpm)) / 1_000_000.0;
        double capShare = (double) maxPpm * p;               // c·f, scaled by PPM·100
        double ret = (double) returnPpm * MAX_SHARE;         // r,   scaled by PPM·100
        if (ret <= capShare * k) return Long.MAX_VALUE;
        double useful = capShare * (double) pot / (ret - capShare * k);
        if (useful >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, (long) Math.ceil(useful));
    }

    /** The part of an amount that goes into the pot, rounded down: the house keeps the odd one. */
    public static long potPart(long amount, int potSharePpm) {
        if (amount <= 0 || potSharePpm <= 0) return 0;
        if (potSharePpm >= 1_000_000) return amount;
        return java.math.BigInteger.valueOf(amount).multiply(java.math.BigInteger.valueOf(potSharePpm))
                .divide(java.math.BigInteger.valueOf(1_000_000)).longValueExact();
    }
}
