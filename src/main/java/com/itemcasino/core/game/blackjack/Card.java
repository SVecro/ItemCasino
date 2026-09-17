package com.itemcasino.core.game.blackjack;

/**
 * One playing card. {@code rank} is 0..12 where 0 is the ace and 12 the king; {@code suit} is 0..3.
 *
 * <p>Packs into a single byte, which is what goes on the wire.
 */
public record Card(int rank, int suit) {

    public static final int RANKS = 13;
    public static final int SUITS = 4;

    public Card {
        if (rank < 0 || rank >= RANKS) throw new IllegalArgumentException("rank " + rank);
        if (suit < 0 || suit >= SUITS) throw new IllegalArgumentException("suit " + suit);
    }

    /** Value with aces counted high; {@link Hand} demotes them as needed. */
    public int hardValue() {
        return rank == 0 ? 11 : Math.min(rank + 1, 10);
    }

    public boolean isAce() { return rank == 0; }

    /** True for 10, J, Q, K — the cards that can complete a dealer natural. */
    public boolean isTenValue() { return rank >= 9; }

    public byte pack() { return (byte) ((suit << 4) | rank); }

    public static Card unpack(byte packed) {
        return new Card(packed & 0x0F, (packed >> 4) & 0x03);
    }

    @Override
    public String toString() {
        return "A23456789TJQK".charAt(rank) + "shdc".substring(suit, suit + 1);
    }
}
