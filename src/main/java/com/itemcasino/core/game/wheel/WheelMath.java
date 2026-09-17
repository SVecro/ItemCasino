package com.itemcasino.core.game.wheel;

import com.itemcasino.core.value.Odds;

/**
 * Geometry for the Upgrader wheel.
 *
 * <p>The outcome is rolled once, on the server, at lock time. The stop angle is then chosen
 * <em>inside</em> the arc that corresponds to that outcome, so the animation is a replay of a
 * decision that has already been written down. A client that skips the animation gains nothing,
 * and the visual can never disagree with the ledger.
 */
public final class WheelMath {

    public static final float TAU = (float) (Math.PI * 2.0);

    /** Keeps the pointer off the exact arc boundary, which reads as a bug even when it is not. */
    private static final float INSET = 0.02f;

    private WheelMath() {}

    /** Angular size of the winning sector, in radians. */
    public static float winArc(int ppm) {
        return TAU * (ppm / (float) Odds.PPM);
    }

    /**
     * @param win  the already-decided outcome
     * @param ppm  the frozen odds
     * @param r01  a uniform sample in [0, 1)
     * @return the final resting angle in [0, TAU)
     */
    public static float stopAngle(boolean win, int ppm, float r01) {
        float arc = winArc(ppm);
        if (win) {
            float span = arc * (1f - 2f * INSET);
            return arc * INSET + r01 * span;
        }
        float loseArc = TAU - arc;
        float span = loseArc * (1f - 2f * INSET);
        return arc + loseArc * INSET + r01 * span;
    }

    /** Inverse of {@link #stopAngle}, used by tests and by the client to colour the result. */
    public static boolean isWinningAngle(float angle, int ppm) {
        float a = normalise(angle);
        return a < winArc(ppm);
    }

    /**
     * The rotation to hand the renderer so that {@code angle} is what ends up under the pointer.
     *
     * <p>It is a negation, and the missing negation was a real bug: the renderer places the sector
     * that starts at wheel angle {@code from} at screen angle {@code rotation + from}, so the
     * sector beneath a pointer fixed at twelve o'clock is the one at {@code -rotation}. Spinning by
     * {@code +angle} therefore showed the mirror image of the decision — a paid win landing on a
     * losing wedge, and, on short odds, a loss landing on gold.
     *
     * <p>Going through these two functions rather than a sign buried in the draw call is what makes
     * the relationship testable without a running client.
     */
    public static float drawRotation(float angle) {
        return -angle;
    }

    /** Which wheel angle sits under the fixed pointer when the wheel is drawn at {@code rotation}. */
    public static float underPointer(float rotation) {
        return normalise(-rotation);
    }

    public static float normalise(float angle) {
        float a = angle % TAU;
        return a < 0 ? a + TAU : a;
    }

    /** Quintic ease-out: fast spin, long settle. */
    public static float easeOutQuint(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u * u * u;
    }

    public static float clamp01(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    /** Linear interpolation used by the client tween. */
    public static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }
}
