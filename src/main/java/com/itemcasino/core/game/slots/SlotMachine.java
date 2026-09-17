package com.itemcasino.core.game.slots;

import com.itemcasino.core.game.Roller;

/**
 * Three reels, spun once.
 *
 * <p>Like every other game here, the spin happens in full the moment the wager is committed: three
 * numbers come out of the roller, the paytable is applied, and the result is written down. What the
 * client then plays is a replay of that — reels that stop one after another on faces already
 * chosen. A client that skips the animation gains nothing, and nothing the player does after the
 * lever can change what the machine already owes.
 */
public final class SlotMachine {

    /** Reels are independent and identical, so the sample space is the strip cubed. */
    public static final int REELS = 3;

    private SlotMachine() {}

    /** Spins all three reels. The roller is the only source of chance in the machine. */
    public static SlotOutcome spin(Roller roller) {
        int left = SlotSymbol.atStripPosition(roller.nextInt(SlotSymbol.STRIP_WEIGHT)).ordinal();
        int middle = SlotSymbol.atStripPosition(roller.nextInt(SlotSymbol.STRIP_WEIGHT)).ordinal();
        int right = SlotSymbol.atStripPosition(roller.nextInt(SlotSymbol.STRIP_WEIGHT)).ordinal();
        return evaluate(left, middle, right);
    }

    /**
     * Reads three faces off the paytable.
     *
     * <p>Separate from {@link #spin} so the same reading can be re-derived from a saved result —
     * which is what makes a machine restored mid-spin pay exactly what it had already decided to.
     */
    public static SlotOutcome evaluate(int left, int middle, int right) {
        if (left == middle && middle == right) {
            SlotSymbol symbol = SlotSymbol.byId(left);
            return new SlotOutcome(left, middle, right, SlotOutcome.Kind.TRIPLE, symbol,
                    symbol.tripleMultiplier());
        }

        int pairId = -1;
        if (left == middle || left == right) pairId = left;
        else if (middle == right) pairId = middle;

        if (pairId >= 0) {
            SlotSymbol symbol = SlotSymbol.byId(pairId);
            int multiplier = symbol.pairMultiplier();
            // A pair that pays nothing is not a pair as far as the player is concerned, and saying
            // "two coal!" while handing over nothing is the kind of thing that reads as a bug.
            return new SlotOutcome(left, middle, right,
                    multiplier > 0 ? SlotOutcome.Kind.PAIR : SlotOutcome.Kind.NOTHING,
                    multiplier > 0 ? symbol : null, multiplier);
        }
        return new SlotOutcome(left, middle, right, SlotOutcome.Kind.NOTHING, null, 0);
    }

    /**
     * The machine's exact return to player, as a fraction over the whole sample space.
     *
     * <p>Computed rather than asserted: the test enumerates every one of the 64³ strip positions
     * and this is what it compares against, so the paytable can never drift away from its edge
     * without something going red.
     *
     * @return {@code [paidWeight, totalWeight]} where the ratio is the return
     */
    public static long[] exactReturn() {
        long paid = 0;
        long total = 0;
        SlotSymbol[] symbols = SlotSymbol.values();
        for (SlotSymbol a : symbols) {
            for (SlotSymbol b : symbols) {
                for (SlotSymbol c : symbols) {
                    long weight = (long) a.weight() * b.weight() * c.weight();
                    total += weight;
                    paid += weight * evaluate(a.ordinal(), b.ordinal(), c.ordinal()).multiplier();
                }
            }
        }
        return new long[] { paid, total };
    }
}
