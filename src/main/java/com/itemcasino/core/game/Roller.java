package com.itemcasino.core.game;

/**
 * The only source of randomness the rules engines are allowed to touch.
 *
 * <p>Keeping this an interface instead of Minecraft's {@code RandomSource} is what lets the whole
 * of {@code com.itemcasino.core} compile and be unit-tested without a game instance, and it is also
 * the seam where a provably-fair HMAC stream can be substituted for the server RNG.
 */
@FunctionalInterface
public interface Roller {

    /** @return a uniformly distributed int in {@code [0, bound)}; bound must be positive. */
    int nextInt(int bound);

    /** @return a uniformly distributed float in {@code [0, 1)}. */
    default float nextFloat() {
        return nextInt(1 << 24) / (float) (1 << 24);
    }

    /** Adapts a {@link java.util.Random} — used by tests and by the provably-fair replay tool. */
    static Roller of(java.util.Random random) {
        return random::nextInt;
    }
}
