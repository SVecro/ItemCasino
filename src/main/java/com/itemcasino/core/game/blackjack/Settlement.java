package com.itemcasino.core.game.blackjack;

/**
 * The result of a hand.
 *
 * @param outcome  what happened, for display
 * @param betUnits 1 normally, 2 after a double down
 * @param dealerTotal the dealer's final total (0 if the dealer never played)
 * @param playerTotal the player's final total
 */
public record Settlement(Outcome outcome, int betUnits, int playerTotal, int dealerTotal) {

    /** Payout numerator over {@link #payDenominator()}, in units of the <em>original</em> wager. */
    public int payNumerator() { return outcome.payNumerator() * betUnits; }

    public int payDenominator() { return outcome.payDenominator(); }

    /** e.g. 2.5 for a natural, 4.0 for a won double down, 0.5 for a surrender. */
    public double multiplier() { return payNumerator() / (double) payDenominator(); }
}
