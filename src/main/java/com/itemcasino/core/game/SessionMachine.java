package com.itemcasino.core.game;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * The legal transitions of a casino session, and the invariants that must hold in every state.
 *
 * <p>This lives in the pure core for one reason: duplication bugs are transition bugs, and a
 * transition table buried inside a block entity can only be tested by standing up a Minecraft
 * server. Here it is exhaustively testable — all 49 state pairs and all 56 state/flag combinations
 * are covered by the unit suite.
 *
 * <pre>
 *   IDLE -> ARMED -> LOCKED -> ROLLING -> SETTLING -> PAYOUT_PENDING -> IDLE
 *             |         |         |           |
 *             +---------+---------+-----------+--------> ABORTED -> IDLE
 * </pre>
 */
public final class SessionMachine {

    private static final Map<GameState, EnumSet<GameState>> ALLOWED = new EnumMap<>(GameState.class);

    static {
        ALLOWED.put(GameState.IDLE, EnumSet.of(GameState.ARMED, GameState.ABORTED));
        ALLOWED.put(GameState.ARMED, EnumSet.of(GameState.IDLE, GameState.LOCKED, GameState.ABORTED));
        ALLOWED.put(GameState.LOCKED, EnumSet.of(GameState.ROLLING, GameState.SETTLING, GameState.ABORTED));
        // ROLLING may not go back to LOCKED: once the outcome is decided it cannot be re-decided.
        ALLOWED.put(GameState.ROLLING, EnumSet.of(GameState.SETTLING, GameState.ABORTED));
        // SETTLING is the one-way door. Nothing returns to it, which is what makes a replayed
        // "animation complete" packet a no-op instead of a second payout.
        ALLOWED.put(GameState.SETTLING, EnumSet.of(GameState.IDLE, GameState.PAYOUT_PENDING, GameState.ABORTED));
        ALLOWED.put(GameState.PAYOUT_PENDING, EnumSet.of(GameState.IDLE, GameState.ABORTED));
        ALLOWED.put(GameState.ABORTED, EnumSet.of(GameState.IDLE, GameState.PAYOUT_PENDING));
    }

    private SessionMachine() {}

    /** @return true when {@code from -> to} is a transition the design permits. */
    public static boolean isLegal(GameState from, GameState to) {
        if (from == null || to == null) return false;
        if (from == to) return false;                       // no self-loops: every step moves
        return ALLOWED.get(from).contains(to);
    }

    /**
     * Checks the state against the fields that must agree with it.
     *
     * @return a human-readable violation, or {@code null} when everything agrees
     */
    public static String violation(GameState state, boolean escrowPresent, boolean payoutPresent,
                                   boolean ownerPresent) {
        if (state == null) return "null state";

        if (state.holdsEscrow() && !escrowPresent && !payoutPresent) {
            return state + " holds a wager but the escrow is empty";
        }
        if (state.acceptsItems() && escrowPresent) {
            return state + " accepts slot input but something is still escrowed";
        }
        if (payoutPresent && state != GameState.PAYOUT_PENDING && state != GameState.SETTLING
                && state != GameState.ABORTED) {
            return state + " has a pending payout but is not PAYOUT_PENDING";
        }
        if (state == GameState.PAYOUT_PENDING && !payoutPresent) {
            return "PAYOUT_PENDING with nothing to pay out";
        }
        if (state != GameState.IDLE && !ownerPresent) {
            return state + " has no owner";
        }
        return null;
    }

    public static boolean invariantsHold(GameState state, boolean escrowPresent,
                                         boolean payoutPresent, boolean ownerPresent) {
        return violation(state, escrowPresent, payoutPresent, ownerPresent) == null;
    }

    /**
     * Where a session restored from disk must land.
     *
     * <p>A session cannot resume an animation across a restart, and must not be left holding an
     * escrow forever. Because the outcome was decided at lock time and saved with the rest of the
     * state, the repair is deterministic: materialise what was already owed and park it.
     */
    public static GameState repairAfterLoad(GameState restored, boolean payoutPresent) {
        if (restored == null) return GameState.IDLE;
        if (!restored.holdsEscrow() && restored != GameState.ABORTED) return restored;
        return payoutPresent ? GameState.PAYOUT_PENDING : GameState.IDLE;
    }
}
