package com.itemcasino.client;

import com.itemcasino.core.game.wheel.WheelMath;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Animation bookkeeping for the screen that is currently open.
 *
 * <p>Purely cosmetic. Every field here is a consequence of a server packet; nothing in this class
 * ever decides anything. A single static instance is enough because a player can only have one
 * container open at a time.
 */
public final class ClientSessionState {

    public enum Phase { IDLE, SPINNING, RESULT }

    private static Phase phase = Phase.IDLE;
    private static long sessionId;
    private static int containerId = -1;
    private static boolean win;
    private static float startAngle;
    private static float targetAngle;
    private static int spinTicks = 60;
    private static float elapsed;
    private static boolean acknowledged;
    private static int winnerSeat = -1;
    /** The dice roll being animated (0..9999), or -1. */
    private static int diceRoll = -1;

    private static int quotedPpm;
    private static Identifier quotedTarget;
    private static long quotedInputValue = -1;
    private static long quotedTargetValue = -1;
    private static List<ItemStack> payout = List.of();
    private static ItemStack wagerGhost = ItemStack.EMPTY;
    @javax.annotation.Nullable private static Reveal pendingReveal;

    /** A settled wager, as the result screen needs it. */
    public record Reveal(List<ItemStack> stacks, int winnerSeat, int tier, long winCents) {}

    private ClientSessionState() {}

    // ------------------------------------------------------------------ quotes

    /**
     * A session has locked. Recording the id here is what makes interactive games work at all:
     * every subsequent client action echoes it, and the server rejects anything that does not
     * match. Blackjack was unplayable past the deal until this was wired up — hit and stand were
     * arriving with id 0 and being dropped.
     */
    public static void beginSession(int containerId, long sessionId) {
        ClientSessionState.containerId = containerId;
        ClientSessionState.sessionId = sessionId;
        ClientSessionState.payout = List.of();
        ClientSessionState.acknowledged = true;   // no animation to acknowledge yet
        pendingReveal = null;
        phase = Phase.IDLE;
    }

    /** The stack the player committed, kept only so the locked slot can show what it is holding. */
    public static void rememberWager(ItemStack stack) {
        wagerGhost = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public static ItemStack wagerGhost() { return wagerGhost; }

    public static void quote(int containerId, Identifier target, int ppm, long inputValue,
                             long targetValue) {
        ClientSessionState.containerId = containerId;
        quotedTarget = target;
        quotedPpm = ppm;
        quotedInputValue = inputValue;
        quotedTargetValue = targetValue;
    }

    public static int quotedPpm() { return quotedPpm; }

    public static Identifier quotedTarget() { return quotedTarget; }

    public static long quotedInputValue() { return quotedInputValue; }

    public static long quotedTargetValue() { return quotedTargetValue; }

    // ------------------------------------------------------------------ spin

    public static void beginSpin(int containerId, long sessionId, boolean win, float stopAngle,
                                 int spinTicks, int fullSpins) {
        ClientSessionState.containerId = containerId;
        ClientSessionState.sessionId = sessionId;
        ClientSessionState.win = win;
        ClientSessionState.startAngle = currentAngle(0F);
        // Land exactly on the server's angle, after a few whole turns purely for the look of it.
        ClientSessionState.targetAngle = startAngle
                + fullSpins * WheelMath.TAU
                + WheelMath.normalise(stopAngle - WheelMath.normalise(startAngle));
        ClientSessionState.spinTicks = Math.max(1, spinTicks);
        ClientSessionState.elapsed = 0F;
        ClientSessionState.acknowledged = false;
        ClientSessionState.payout = List.of();
        ClientSessionState.winnerSeat = -1;
        ClientSessionState.diceRoll = -1;
        pendingReveal = null;
        phase = Phase.SPINNING;
    }

    /** A dice roll: the number the counter runs to, already decided on the server. */
    public static void beginRoll(int containerId, long sessionId, boolean win, int roll, int rollTicks) {
        beginSpin(containerId, sessionId, win, 0F, rollTicks, 0);
        diceRoll = roll;
    }

    public static int diceRoll() { return diceRoll; }

    /** How far through the animation, eased: 0 at the throw, 1 when it has landed. */
    public static float progress(float partialTick) {
        if (phase == Phase.IDLE) return 1F;
        float t = WheelMath.clamp01((elapsed + partialTick) / spinTicks);
        return WheelMath.easeOutQuint(t);
    }

    /**
     * A duel's spin. The winning seat decides the face the coin stops on — seat A is the near face,
     * seat B the far one — and each client works out whether that was them by comparing the seat it
     * holds. A "win" flag would have to be different in every copy of the packet.
     */
    public static void beginDuel(int containerId, long sessionId, int winnerSeat, int spinTicks) {
        beginSpin(containerId, sessionId, winnerSeat == 0, winnerSeat == 0 ? 0.4F : 3.5F,
                spinTicks, 7);
        ClientSessionState.winnerSeat = winnerSeat;
    }

    public static int winnerSeat() { return winnerSeat; }

    public static void tick() {
        if (phase == Phase.SPINNING) {
            elapsed += 1F;
            if (elapsed >= spinTicks) phase = Phase.RESULT;
        }
    }

    /** True exactly once, the first time the animation has finished: the cue to acknowledge. */
    public static boolean consumeAcknowledgement() {
        if (phase != Phase.RESULT || acknowledged) return false;
        acknowledged = true;
        return true;
    }

    public static float currentAngle(float partialTick) {
        if (phase == Phase.IDLE) return 0F;
        float t = WheelMath.clamp01((elapsed + partialTick) / spinTicks);
        return WheelMath.lerp(WheelMath.easeOutQuint(t), startAngle, targetAngle);
    }

    public static Phase phase() { return phase; }

    public static boolean won() { return win; }

    public static long sessionId() { return sessionId; }

    public static int containerId() { return containerId; }

    public static List<ItemStack> payout() { return payout; }

    /**
     * The server has settled. What it paid is kept for the screen to reveal when its own animation
     * is over: a spin still turning is left to finish, since the settle can arrive a moment before
     * the last frame of it.
     */
    public static void payoutReady(long sessionId, List<ItemStack> stacks, int winnerSeat, int tier, long winCents) {
        // id 0 means this screen was opened fresh onto a payout parked by an earlier session.
        if (ClientSessionState.sessionId != 0 && sessionId != ClientSessionState.sessionId) return;
        payout = List.copyOf(stacks);
        if (phase != Phase.SPINNING) phase = Phase.RESULT;
        pendingReveal = new Reveal(payout, winnerSeat, tier, winCents);
    }

    /** The settled result, once, when no spin is still turning; null otherwise. */
    @javax.annotation.Nullable
    public static Reveal consumeReveal() {
        if (pendingReveal == null || phase == Phase.SPINNING) return null;
        Reveal reveal = pendingReveal;
        pendingReveal = null;
        return reveal;
    }

    public static void abort() {
        pendingReveal = null;
        phase = Phase.IDLE;
        payout = List.of();
        acknowledged = true;
    }

    public static void reset() {
        phase = Phase.IDLE;
        sessionId = 0;
        containerId = -1;
        elapsed = 0F;
        acknowledged = true;
        payout = List.of();
        quotedPpm = 0;
        quotedTarget = null;
        quotedInputValue = -1;
        quotedTargetValue = -1;
        wagerGhost = ItemStack.EMPTY;
        winnerSeat = -1;
        diceRoll = -1;
        pendingReveal = null;
    }
}
