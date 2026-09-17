package com.itemcasino.core.game.jackpot;

/**
 * How likely a bought draw is to take the pot.
 *
 * <h2>The rule</h2>
 * Your offering goes into the pot <em>before</em> the draw, and your chance is
 * {@code return × offered / pot}, where {@code pot} already includes what you just added. That one
 * line is chosen so the arithmetic comes out flat:
 *
 * <pre>
 *   expected winnings = chance × pot
 *                     = (return × offered / pot) × pot
 *                     = return × offered
 * </pre>
 *
 * <p>So a draw hands back a fixed fraction of what you fed it <em>whatever the pot is worth</em> —
 * the same ten percent edge the tables take, and no more attractive on a huge pot than on an empty
 * one. Without the offering counting toward the denominator the first player to find an empty vault
 * could buy it outright; with it, the very first draw is simply a bet against yourself.
 *
 * <p>The cap is the other half. Uncapped, feeding the pot half its own worth would buy a coin flip
 * for the lot, which turns a jackpot meant to take months into a purchase. Capped, no single draw
 * can ever be likely, and the only way to a big pot is a lot of small chances.
 */
public final class JackpotOdds {

    /** Parts per million, the same unit the odds engine uses everywhere else. */
    public static final int PPM = 1_000_000;

    private JackpotOdds() {}

    /**
     * @param offeredValue the worth of what the player just fed the vault
     * @param potValue     the worth of the pot <em>including</em> that offering
     * @param returnPpm    the fraction of the offering a draw returns in expectation
     * @param maxPpm       the hard ceiling on a single draw
     * @return the chance of taking the pot, in parts per million
     */
    public static int drawPpm(long offeredValue, long potValue, int returnPpm, int maxPpm) {
        if (offeredValue <= 0 || potValue <= 0 || returnPpm <= 0 || maxPpm <= 0) return 0;
        if (offeredValue >= potValue) return maxPpm;

        // returnPpm * offered / pot, kept exact by dividing before multiplying only when the
        // multiplication would overflow. Value points reach into the billions on a busy server.
        long chance;
        if (offeredValue <= Long.MAX_VALUE / returnPpm) {
            chance = offeredValue * returnPpm / potValue;
        } else {
            chance = offeredValue / potValue * returnPpm;
        }
        if (chance <= 0) return 0;
        return (int) Math.min(maxPpm, chance);
    }

    /**
     * What a draw is worth to the player, as a fraction of the pot in ppm — used by the screen to
     * show the trade honestly rather than only showing the flattering half of it.
     */
    public static long expectedReturn(long offeredValue, long potValue, int returnPpm, int maxPpm) {
        long chance = drawPpm(offeredValue, potValue, returnPpm, maxPpm);
        return chance <= 0 ? 0 : chance * potValue / PPM;
    }

    /**
     * The largest offering still worth making, against a pot of this size.
     *
     * <p>The flat-return property above holds only while the cap is slack. Past it, every extra
     * point fed in buys nothing at all — the chance is already at the ceiling — so the draw quietly
     * stops returning its advertised share and starts returning less and less. That is not a
     * trade-off, it is the machine taking money for nothing, and the fix is to refuse the surplus
     * rather than to keep it.
     *
     * <p>Solving {@code return x offered / (pot + offered) = max} for the offering gives
     * {@code pot x max / (return - max)}, which is where the ceiling first bites.
     *
     * @param potValue the pot <em>before</em> the offering
     * @return the most that should be taken, always at least one unit of value
     */
    public static long maxUsefulOffer(long potValue, int returnPpm, int maxPpm) {
        if (potValue <= 0 || returnPpm <= maxPpm) return Long.MAX_VALUE;
        long numerator = potValue / (returnPpm - maxPpm);
        long useful = numerator <= Long.MAX_VALUE / maxPpm
                ? numerator * maxPpm
                : Long.MAX_VALUE;
        return Math.max(1, useful);
    }
}
