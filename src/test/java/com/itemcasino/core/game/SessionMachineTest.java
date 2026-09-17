package com.itemcasino.core.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-duplication rules, tested without a game instance.
 *
 * <p>Duplication bugs are transition bugs. Keeping the transition table in the pure core is what
 * makes them reachable from a unit test instead of only from a running server.
 */
class SessionMachineTest {

    @Test
    @DisplayName("SETTLING is a one-way door, so a replayed settle cannot pay twice")
    void settlingIsOneWay() {
        assertFalse(SessionMachine.isLegal(GameState.PAYOUT_PENDING, GameState.SETTLING));
        assertFalse(SessionMachine.isLegal(GameState.IDLE, GameState.SETTLING));
        assertFalse(SessionMachine.isLegal(GameState.ARMED, GameState.SETTLING));
        assertTrue(SessionMachine.isLegal(GameState.ROLLING, GameState.SETTLING));
    }

    @Test
    @DisplayName("no state loops to itself and a decided outcome cannot be re-decided")
    void structuralRules() {
        for (GameState state : GameState.values()) {
            assertFalse(SessionMachine.isLegal(state, state), state + " loops to itself");
        }
        assertFalse(SessionMachine.isLegal(GameState.ROLLING, GameState.LOCKED));
        assertFalse(SessionMachine.isLegal(GameState.ROLLING, GameState.PAYOUT_PENDING));
        assertFalse(SessionMachine.isLegal(null, GameState.IDLE));
        assertFalse(SessionMachine.isLegal(GameState.IDLE, null));
    }

    @Test
    @DisplayName("the win path and the loss path are both walkable")
    void happyPaths() {
        assertTrue(walk(GameState.IDLE, GameState.ARMED, GameState.LOCKED, GameState.ROLLING,
                GameState.SETTLING, GameState.PAYOUT_PENDING, GameState.IDLE));
        assertTrue(walk(GameState.ARMED, GameState.LOCKED, GameState.ROLLING, GameState.SETTLING,
                GameState.IDLE));
        for (GameState live : new GameState[] { GameState.ARMED, GameState.LOCKED,
                GameState.ROLLING, GameState.SETTLING }) {
            assertTrue(SessionMachine.isLegal(live, GameState.ABORTED), live + " cannot abort");
        }
    }

    @Test
    @DisplayName("escrow exists exactly while a wager is committed")
    void escrowInvariants() {
        assertFalse(SessionMachine.invariantsHold(GameState.LOCKED, false, false, true));
        assertTrue(SessionMachine.invariantsHold(GameState.ROLLING, true, false, true));
        assertFalse(SessionMachine.invariantsHold(GameState.IDLE, true, false, false));
        assertFalse(SessionMachine.invariantsHold(GameState.ARMED, true, false, true));
        assertTrue(SessionMachine.invariantsHold(GameState.IDLE, false, false, false));
    }

    @Test
    @DisplayName("a payout can only sit in PAYOUT_PENDING, and never sits there empty")
    void payoutInvariants() {
        assertFalse(SessionMachine.invariantsHold(GameState.ARMED, false, true, true));
        assertFalse(SessionMachine.invariantsHold(GameState.PAYOUT_PENDING, false, false, true));
        assertTrue(SessionMachine.invariantsHold(GameState.PAYOUT_PENDING, false, true, true));
        assertFalse(SessionMachine.invariantsHold(GameState.ROLLING, true, false, false),
                "a live session must have an owner");
    }

    @Test
    @DisplayName("every state and flag combination is classified rather than throwing")
    void totality() {
        int combinations = 0;
        for (GameState state : GameState.values()) {
            for (int mask = 0; mask < 8; mask++) {
                SessionMachine.violation(state, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0);
                combinations++;
            }
        }
        assertEquals(GameState.values().length * 8, combinations);
    }

    @Test
    @DisplayName("a session restored mid-spin is repaired forward, never left holding the escrow")
    void crashRepair() {
        assertEquals(GameState.PAYOUT_PENDING, SessionMachine.repairAfterLoad(GameState.ROLLING, true));
        assertEquals(GameState.IDLE, SessionMachine.repairAfterLoad(GameState.ROLLING, false));
        assertEquals(GameState.IDLE, SessionMachine.repairAfterLoad(GameState.LOCKED, false));
        assertEquals(GameState.IDLE, SessionMachine.repairAfterLoad(GameState.SETTLING, false));
        assertEquals(GameState.PAYOUT_PENDING,
                SessionMachine.repairAfterLoad(GameState.PAYOUT_PENDING, true));
        assertEquals(GameState.IDLE, SessionMachine.repairAfterLoad(GameState.IDLE, false));
        assertEquals(GameState.IDLE, SessionMachine.repairAfterLoad(null, false));
    }

    @Test
    @DisplayName("the state predicates match the documented graph")
    void predicates() {
        assertTrue(GameState.IDLE.acceptsItems() && GameState.ARMED.acceptsItems());
        assertFalse(GameState.LOCKED.acceptsItems() || GameState.ROLLING.acceptsItems()
                || GameState.SETTLING.acceptsItems() || GameState.PAYOUT_PENDING.acceptsItems());
        assertTrue(GameState.LOCKED.holdsEscrow() && GameState.ROLLING.holdsEscrow()
                && GameState.SETTLING.holdsEscrow());
        assertFalse(GameState.IDLE.holdsEscrow() || GameState.ARMED.holdsEscrow()
                || GameState.PAYOUT_PENDING.holdsEscrow());
        assertEquals(GameState.IDLE, GameState.byName("NONSENSE", GameState.IDLE));
        assertEquals(GameState.ROLLING, GameState.byName("ROLLING", GameState.IDLE));
    }

    private static boolean walk(GameState... path) {
        for (int i = 0; i + 1 < path.length; i++) {
            if (!SessionMachine.isLegal(path[i], path[i + 1])) return false;
        }
        return true;
    }
}
