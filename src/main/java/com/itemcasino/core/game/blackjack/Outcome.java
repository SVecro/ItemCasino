package com.itemcasino.core.game.blackjack;

/**
 * How a hand ended and what it pays, as an exact rational applied to the effective bet.
 *
 * <p>The multiplier <em>includes</em> the returned stake: {@link #WIN} is 2/1, not 1/1, because the
 * player gets their wager back plus an equal amount. Keeping this rational rather than a double is
 * what lets {@code PayoutMath} split a 3:2 payout on an odd item count without silently losing
 * value.
 */
public enum Outcome {
    /** Two-card 21 against a non-natural dealer: 3:2, so 5/2 including the stake. */
    PLAYER_BLACKJACK(5, 2),
    WIN(2, 1),
    PUSH(1, 1),
    LOSS(0, 1),
    DEALER_BLACKJACK(0, 1),
    PLAYER_BUST(0, 1),
    DEALER_BUST(2, 1),
    SURRENDER(1, 2);

    private final int num;
    private final int den;

    Outcome(int num, int den) { this.num = num; this.den = den; }

    public int payNumerator() { return num; }

    public int payDenominator() { return den; }

    public boolean isWin() { return num > den; }

    public boolean isPush() { return num == den; }

    public static Outcome byId(int id) {
        Outcome[] values = values();
        return (id < 0 || id >= values.length) ? null : values[id];
    }
}
