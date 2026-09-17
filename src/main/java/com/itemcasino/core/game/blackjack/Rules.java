package com.itemcasino.core.game.blackjack;

/**
 * Table rules. Defaults are S17 / 3:2 / double on any two / late surrender / dealer peek, which is
 * a ~0.5 % house edge under basic strategy — deliberately the friendliest of the three games.
 *
 * @param decks              shoe size; 1 with a per-hand reshuffle makes counting pointless
 * @param dealerHitsSoft17   H17 raises the house edge by roughly 0.2 %
 * @param allowDouble        double down on the first two cards
 * @param allowSurrender     late surrender, first two cards only
 * @param dealerPeek         peek for a natural on an ace or ten up-card before the player acts
 */
public record Rules(int decks, boolean dealerHitsSoft17, boolean allowDouble,
                    boolean allowSurrender, boolean dealerPeek) {

    public static final Rules DEFAULT = new Rules(1, false, true, true, true);

    public Rules {
        if (decks < 1 || decks > 8) throw new IllegalArgumentException("decks " + decks);
    }
}
