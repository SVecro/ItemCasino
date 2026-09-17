package com.itemcasino.core.game.blackjack;

import com.itemcasino.core.game.Roller;

/**
 * A shuffled shoe of {@code decks} standard decks.
 *
 * <p>Shuffled with the modern (descending) form of Fisher-Yates, which is the only in-place shuffle
 * that produces a uniform permutation. The ascending variant and the "swap with any index" variant
 * both bias the result.
 */
public final class Shoe {

    private final byte[] cards;
    private int cursor;

    public Shoe(int decks, Roller roller) {
        if (decks < 1) throw new IllegalArgumentException("decks " + decks);
        this.cards = new byte[decks * Card.RANKS * Card.SUITS];
        int k = 0;
        for (int d = 0; d < decks; d++) {
            for (int s = 0; s < Card.SUITS; s++) {
                for (int r = 0; r < Card.RANKS; r++) {
                    cards[k++] = new Card(r, s).pack();
                }
            }
        }
        shuffle(roller);
    }

    private void shuffle(Roller roller) {
        for (int i = cards.length - 1; i > 0; i--) {
            int j = roller.nextInt(i + 1);
            byte t = cards[i];
            cards[i] = cards[j];
            cards[j] = t;
        }
    }

    public Card draw() {
        if (cursor >= cards.length) throw new IllegalStateException("shoe exhausted");
        return Card.unpack(cards[cursor++]);
    }

    public int remaining() { return cards.length - cursor; }

    public int size() { return cards.length; }

    /** True once the shoe has been dealt past {@code penetration} of its length. */
    public boolean needsReshuffle(double penetration) {
        return cursor >= cards.length * penetration;
    }
}
