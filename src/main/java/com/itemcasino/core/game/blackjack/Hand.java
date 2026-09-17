package com.itemcasino.core.game.blackjack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A growable hand of cards with the standard soft/hard totalling rules. */
public final class Hand {

    private final List<Card> cards = new ArrayList<>(8);

    public void add(Card card) { cards.add(card); }

    public int size() { return cards.size(); }

    public Card get(int i) { return cards.get(i); }

    public List<Card> cards() { return Collections.unmodifiableList(cards); }

    /** Best total not exceeding 21 if one exists, otherwise the minimum (busted) total. */
    public int total() {
        int total = 0, aces = 0;
        for (Card c : cards) {
            total += c.hardValue();
            if (c.isAce()) aces++;
        }
        while (total > 21 && aces > 0) { total -= 10; aces--; }
        return total;
    }

    /** True when an ace is still being counted as 11, i.e. the hand cannot bust on the next card. */
    public boolean isSoft() {
        int total = 0, aces = 0;
        for (Card c : cards) {
            total += c.hardValue();
            if (c.isAce()) aces++;
        }
        while (total > 21 && aces > 0) { total -= 10; aces--; }
        return aces > 0;
    }

    public boolean isBust() { return total() > 21; }

    /** Exactly two cards totalling 21 — a natural, which pays 3:2 and beats a drawn 21. */
    public boolean isNatural() { return cards.size() == 2 && total() == 21; }

    @Override
    public String toString() { return cards + "=" + total() + (isSoft() ? "s" : ""); }
}
