package com.itemcasino.core.game;

import java.math.BigInteger;

/**
 * The rules of the mine field: a five by five board, a number of hidden mines the player chooses,
 * and a payout that climbs with every safe tile turned over.
 *
 * <h2>The multiplier</h2>
 * After {@code k} safe tiles with {@code m} mines, the fair multiplier is the inverse of the chance
 * of having survived that far:
 *
 * <pre>fair(k) = C(25, k) / C(25 − m, k) = Π (25 − i) / (25 − m − i),  i = 0 .. k−1</pre>
 *
 * and the table pays {@code (1 − edge) × fair(k)}, rounded down to the ppm. So cashing out after
 * any number of tiles returns exactly {@code 1 − edge} of the stake on average — the edge does not
 * depend on how brave the player is. With few mines each step is a light nudge (×1.10 for the first
 * tile with three mines); with many it is a real leap.
 *
 * <p>Zero tiles is ×1 with no edge: backing out before turning anything over is a refund, since the
 * player has learnt nothing.

 *
 * <p>Arithmetic is exact ({@link BigInteger}) so the server's payout and the client's label can never
 * disagree by a rounding.
 */
public final class MineField {

    public static final int SIDE = 5;
    public static final int TILES = SIDE * SIDE;
    public static final int MIN_MINES = 1;
    public static final int MAX_MINES = TILES - 1;
    public static final long PPM = 1_000_000L;

    private MineField() {}

    public static int clampMines(int mines) {
        return Math.max(MIN_MINES, Math.min(MAX_MINES, mines));
    }

    public static int safeTiles(int mines) {
        return TILES - clampMines(mines);
    }

    /**
     * @param revealed safe tiles turned over so far
     * @param edgePpm  the house's share, e.g. 30 000 for 3 %
     * @param capPpm   the largest multiplier the table will pay
     * @return the multiplier in ppm ({@code 1 000 000} is ×1)
     */
    public static long multiplierPpm(int mines, int revealed, int edgePpm, long capPpm) {
        if (revealed <= 0) return PPM;
        int m = clampMines(mines);
        int k = Math.min(revealed, TILES - m);
        BigInteger num = BigInteger.valueOf(PPM - Math.max(0, Math.min(edgePpm, (int) PPM)));
        BigInteger den = BigInteger.ONE;
        for (int i = 0; i < k; i++) {
            num = num.multiply(BigInteger.valueOf(TILES - i));
            den = den.multiply(BigInteger.valueOf(TILES - m - i));
        }
        BigInteger value = num.divide(den);
        BigInteger cap = BigInteger.valueOf(Math.max(PPM, capPpm));
        return value.min(cap).longValueExact();
    }

    /** True once the next tile could not raise the payout: the cap is reached, or the board is clear. */
    public static boolean isFinished(int mines, int revealed, int edgePpm, long capPpm) {
        if (revealed >= safeTiles(mines)) return true;
        return revealed > 0 && multiplierPpm(mines, revealed, edgePpm, capPpm) >= capPpm;
    }

    /**
     * The chance, as an exact fraction {@code [numerator, denominator]}, of surviving {@code revealed}
     * picks. For tests and read-outs; the game itself never needs it.
     */
    public static BigInteger[] survival(int mines, int revealed) {
        int m = clampMines(mines);
        BigInteger num = BigInteger.ONE;
        BigInteger den = BigInteger.ONE;
        for (int i = 0; i < revealed; i++) {
            num = num.multiply(BigInteger.valueOf(TILES - m - i));
            den = den.multiply(BigInteger.valueOf(TILES - i));
        }
        return new BigInteger[] { num, den };
    }

    /** Places {@code mines} mines uniformly at random. Bit {@code t} set means tile {@code t} is a mine. */
    public static int layout(int mines, Roller roller) {
        int m = clampMines(mines);
        int[] tiles = new int[TILES];
        for (int i = 0; i < TILES; i++) tiles[i] = i;
        int mask = 0;
        for (int i = 0; i < m; i++) {                 // partial Fisher-Yates: the first m are mines
            int j = i + roller.nextInt(TILES - i);
            int t = tiles[i];
            tiles[i] = tiles[j];
            tiles[j] = t;
            mask |= 1 << tiles[i];
        }
        return mask;
    }

    public static boolean isMine(int mineMask, int tile) {
        return tile >= 0 && tile < TILES && (mineMask >>> tile & 1) != 0;
    }

    /**
     * The largest chip stake, in hundredths, whose first tile still pays within the ceiling. A bigger
     * stake could never turn a tile at all, so it is not taken.
     */
    public static long maxStakeCents(int mines, int edgePpm, long capPpm, long ceilingCents) {
        long first = multiplierPpm(mines, 1, edgePpm, capPpm);
        if (ceilingCents <= 0 || first <= 0) return 0;
        BigInteger most = BigInteger.valueOf(ceilingCents).multiply(BigInteger.valueOf(PPM))
                .divide(BigInteger.valueOf(first));
        return most.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    /**
     * True when one more safe tile would pay more than the ceiling. The board then stops there and is
     * cashed out at the multiplier already earned, which keeps every step at the table's own edge:
     * shaving the last step's multiplier down to the ceiling would make that step worse than the rest.
     */
    public static boolean nextTileOverCeiling(int mines, int revealed, int edgePpm, long capPpm,
                                              long stakeCents, long ceilingCents) {
        if (stakeCents <= 0) return false;
        if (revealed >= safeTiles(mines)) return false;
        long next = multiplierPpm(mines, revealed + 1, edgePpm, capPpm);
        return com.itemcasino.core.chips.Chips.payout(stakeCents, next) > ceilingCents;
    }

    public static boolean isTile(int tile) {
        return tile >= 0 && tile < TILES;
    }
}
