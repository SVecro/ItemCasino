package com.itemcasino.core.game.blackjack;

/** Phases of a single blackjack hand. */
public enum BlackjackPhase {
    /** Before {@code deal()} has been called. */
    WAITING,
    /** The player may act. */
    PLAYER_TURN,
    /** The hole card is revealed and the dealer draws to policy. Transient. */
    DEALER_TURN,
    /** The hand is over; {@code settlement()} is available. */
    SETTLED
}
