package com.itemcasino.core.game;

/**
 * The shared session lifecycle. Every table runs exactly one session at a time.
 *
 * <pre>
 *   IDLE -> ARMED -> LOCKED -> ROLLING -> SETTLING -> PAYOUT_PENDING -> IDLE
 *             |         |         |           |
 *             +---------+---------+-----------+--------> ABORTED
 * </pre>
 *
 * Invariants (asserted in dev, silently enforced in production):
 * <ul>
 *   <li>{@code escrow.isEmpty() == (state is IDLE or ARMED)}</li>
 *   <li>{@code !payout.isEmpty() implies state == PAYOUT_PENDING}</li>
 *   <li>the input slot accepts and releases items only while {@link #acceptsItems()}</li>
 * </ul>
 */
public enum GameState {
    /** No wager, no owner. */
    IDLE,
    /** A stack is in the slot and (for the Upgrader) a target has been quoted. Nothing committed. */
    ARMED,
    /** The stack has moved to escrow, the odds are frozen, the outcome may already be decided. */
    LOCKED,
    /** The outcome is decided server-side; the client is animating, or the player is acting. */
    ROLLING,
    /** Materialising the payout. Transient, never persists across a tick boundary in practice. */
    SETTLING,
    /** Winnings sit in the block entity waiting to be claimed. */
    PAYOUT_PENDING,
    /** Forced teardown (block broken, server reload, error). Escrow is refunded. */
    ABORTED;

    /** True while the input slot may be filled or emptied by the player. */
    public boolean acceptsItems() {
        return this == IDLE || this == ARMED;
    }

    /** True while a wager is committed and must not be lost. */
    public boolean holdsEscrow() {
        return this == LOCKED || this == ROLLING || this == SETTLING;
    }

    public static GameState byName(String name, GameState fallback) {
        for (GameState s : values()) if (s.name().equals(name)) return s;
        return fallback;
    }
}
