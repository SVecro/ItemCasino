package com.itemcasino.core.game;

/**
 * A {@link Roller} that is a pure function of one {@code long}.
 *
 * <p>Exists for exactly one reason: a blackjack hand has to survive a restart without handing the
 * player a free option. Persisting the hand as "seed plus the actions taken so far" lets the server
 * rebuild the identical shoe, the identical deal and the identical hole card after a reload, so
 * pulling the plug on a bad hand changes nothing. The seed never leaves the server.
 *
 * <p>SplitMix64 for the stream, and {@code java.util.Random}'s rejection loop for an unbiased
 * bounded int. Both are specified here rather than borrowed, so a JDK or Minecraft update can never
 * change what a saved seed deals.
 */
public final class SeededRoller implements Roller {

    private long state;

    public SeededRoller(long seed) {
        this.state = seed;
    }

    private long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound " + bound);
        int bits;
        int value;
        do {
            bits = (int) (nextLong() >>> 33);          // 31 uniformly random bits, never negative
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);       // reject the short last bucket
        return value;
    }
}
