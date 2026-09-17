package com.itemcasino.client.render;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A number the player sets on a table — the Vault's share, the mine count — and the plumbing that
 * makes setting it bearable: hold a button and it repeats, then speeds up; drag or scroll and the
 * screen follows at once; the server hears about it a few times a second, not once a pixel.
 *
 * <p>The value shown is the client's own until the server's copy catches up. The server remains the
 * only authority: it clamps, it refuses outside the betting window, and if it never agrees the local
 * value is dropped after a second and a half and the screen shows what the server has.
 */
public final class ValueStepper {

    /** Ticks a button must be held before it starts repeating. */
    private static final int REPEAT_DELAY = 8;
    /** Ticks between repeats. */
    private static final int REPEAT_EVERY = 2;
    /** Ticks after which a held button moves by the fast step. */
    private static final int FAST_AFTER = 30;
    /** Ticks between two sends while the value is still moving (the rate limit is 20 packets/s). */
    private static final int SEND_EVERY = 3;
    private static final int PENDING_TICKS = 30;

    private final IntSupplier min;
    private final IntSupplier max;
    private final IntSupplier server;
    private final IntConsumer sender;

    private boolean local;
    private int value;
    private int pendingTicks;
    private boolean dirty;
    private int sinceSend = SEND_EVERY;

    private int holdDirection;
    private int holdStep;
    private int holdFastStep;
    private int holdTicks;
    private BooleanSupplier stillHeld = () -> false;

    public ValueStepper(int min, int max, IntSupplier server, IntConsumer sender) {
        this(() -> min, () -> max, server, sender);
    }

    /** Bounds read live, for a table whose limits come from the server's config. */
    public ValueStepper(IntSupplier min, IntSupplier max, IntSupplier server, IntConsumer sender) {
        this.min = min;
        this.max = max;
        this.server = server;
        this.sender = sender;
    }

    public int min() { return min.getAsInt(); }

    public int max() { return Math.max(min(), max.getAsInt()); }

    public int value() {
        return local ? value : clamp(server.getAsInt());
    }

    private int clamp(int v) {
        return Math.max(min(), Math.min(max(), v));
    }

    /** Sets the shown value at once; the server is told on the next send window. */
    public void set(int wanted) {
        int v = clamp(wanted);
        if (v == value()) return;
        local = true;
        value = v;
        dirty = true;
        pendingTicks = PENDING_TICKS;
    }

    public void nudge(int direction, int step) {
        set(value() + direction * Math.max(1, step));
    }

    /**
     * A button went down: one step now, and more while {@code held} stays true.
     *
     * @param fastStep the step once the button has been held for a second and a half
     */
    public void press(int direction, int step, int fastStep, BooleanSupplier held) {
        nudge(direction, step);
        holdDirection = direction;
        holdStep = step;
        holdFastStep = Math.max(step, fastStep);
        holdTicks = 0;
        stillHeld = held;
    }

    /** The mouse came up: stop repeating and send the final value without waiting. */
    public void release() {
        holdDirection = 0;
        flush();
    }

    public void flush() {
        if (!dirty) return;
        sender.accept(value);
        dirty = false;
        sinceSend = 0;
    }

    /** Sends the shown value now even if it has not changed, e.g. after something else it is packed with did. */
    public void resend() {
        dirty = true;
        value = value();
        local = true;
        pendingTicks = PENDING_TICKS;
        flush();
    }

    /** Once per client tick. */
    public void tick() {
        if (holdDirection != 0) {
            if (!stillHeld.getAsBoolean()) {
                release();
            } else {
                holdTicks++;
                if (holdTicks >= REPEAT_DELAY && holdTicks % REPEAT_EVERY == 0) {
                    nudge(holdDirection, holdTicks >= FAST_AFTER ? holdFastStep : holdStep);
                }
            }
        }
        sinceSend++;
        if (dirty && sinceSend >= SEND_EVERY) flush();
        if (local && !dirty && (server.getAsInt() == value || --pendingTicks <= 0)) {
            local = false;
        }
    }
}
