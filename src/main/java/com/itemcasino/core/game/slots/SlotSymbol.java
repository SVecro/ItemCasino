package com.itemcasino.core.game.slots;

/**
 * The six faces on a reel, with everything the machine's economics depend on.
 *
 * <p>Each symbol carries its own weight on the strip and its own payout at three of a kind and at
 * two, all as whole multipliers of the wager <em>including the stake</em> — so 1 is your money back
 * and 0 is a loss, exactly like {@code Outcome} in blackjack. Keeping them here rather than in a
 * config file is deliberate: the weights and the payouts together <em>are</em> the house edge, and
 * splitting them across two places is how a machine ends up quietly paying 140%.
 *
 * <p>The numbers below were solved for a 10% edge — the same cut the other tables take — and
 * {@code SlotMachineTest} recomputes the return exactly, by enumeration, so an edit that breaks it
 * fails the build rather than the server's economy.
 *
 * <h2>Which pairs pay, and why not all of them</h2>
 * Pairs pay from iron up. Two versions of this table were wrong before this one, in opposite
 * directions, and both were instructive.
 *
 * <p>The first paid nothing for any pair below gold and simply said "no match" — two matching
 * symbols sitting on the payline while the machine denied there was a match at all. The second
 * paid every pair the stake back, which fixed the reading and broke the game: a pair of coal alone
 * turns up on three spins in ten, so nearly two thirds of all spins returned something, almost
 * always exactly what went in. Nothing was at stake any more.
 *
 * <p>So the rule is now a sentence rather than a table — <em>pairs pay from iron up</em> — which
 * makes a low pair an obvious near miss instead of an apparent bug, and puts the losses back: four
 * spins in five take the wager. The budget that used to go on handing stakes back went into the
 * triples instead, which is why they are worth several times what they were.
 */
public enum SlotSymbol {

    COAL(26, 4, 0),
    COPPER(16, 12, 0),
    /** The lowest face whose pair pays. Below this a pair is a near miss, and reads as one. */
    IRON(12, 17, 1),
    GOLD(7, 40, 4),
    DIAMOND(2, 250, 12),
    STAR(1, 800, 30);

    /** Total weight across one reel; three identical reels, so the space is this cubed. */
    public static final int STRIP_WEIGHT = 64;

    private final int weight;
    private final int tripleMultiplier;
    private final int pairMultiplier;

    SlotSymbol(int weight, int tripleMultiplier, int pairMultiplier) {
        this.weight = weight;
        this.tripleMultiplier = tripleMultiplier;
        this.pairMultiplier = pairMultiplier;
    }

    public int weight() { return weight; }

    /** Multiplier for three of these, stake included. */
    public int tripleMultiplier() { return tripleMultiplier; }

    /** Multiplier for exactly two of these, stake included. Zero means the pair pays nothing. */
    public int pairMultiplier() { return pairMultiplier; }

    public static SlotSymbol byId(int id) {
        SlotSymbol[] values = values();
        return (id < 0 || id >= values.length) ? COAL : values[id];
    }

    /** Picks the symbol at a point on the weighted strip; {@code roll} must be in [0, 64). */
    public static SlotSymbol atStripPosition(int roll) {
        int remaining = roll;
        for (SlotSymbol symbol : values()) {
            remaining -= symbol.weight;
            if (remaining < 0) return symbol;
        }
        return COAL;
    }
}
