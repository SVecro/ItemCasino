package com.itemcasino.core.game.slots;

/**
 * What a spin came to: the three faces, and what they pay.
 *
 * <p>{@code multiplier} includes the stake, so a loss is 0 and a returned stake is 1. It is a whole
 * number rather than a rational because every entry in the paytable is — which is one fewer place
 * for a rounding policy to lose someone half a diamond.
 *
 * <p>{@code paying} is null when nothing paid. No annotation on it: this package carries no
 * dependency at all, not even on an annotations jar, which is what lets it be compiled and tested
 * without Minecraft on the classpath.
 */
public record SlotOutcome(int left, int middle, int right, Kind kind,
                          SlotSymbol paying, int multiplier) {

    public enum Kind {
        /** Three of a kind. */
        TRIPLE,
        /** Exactly two of a kind. */
        PAIR,
        /** Three different faces, or a pair whose symbol pays nothing. */
        NOTHING
    }

    public boolean isWin() { return multiplier > 1; }

    public boolean paysAnything() { return multiplier > 0; }

    public SlotSymbol leftSymbol() { return SlotSymbol.byId(left); }

    public SlotSymbol middleSymbol() { return SlotSymbol.byId(middle); }

    public SlotSymbol rightSymbol() { return SlotSymbol.byId(right); }
}
