package com.itemcasino.session;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import java.security.SecureRandom;

/**
 * The source of every casino outcome: a {@link SecureRandom}, behind the {@link RandomSource} the
 * games already take.
 *
 * <p>The games used to draw from the level's own random source. That is a {@code LegacyRandomSource},
 * the 48-bit linear congruential generator of {@code java.util.Random}, shared with everything else
 * the world does, and the Upgrader sent a nearly raw output of it to the client with every spin (the
 * stop angle is a linear function of {@code nextFloat()}). Two such outputs from the same tick pin
 * down its state, and everything drawn after them in that tick, a mine field's layout included, can
 * be computed. A cryptographic generator gives away nothing about its next output, whatever a
 * client sees of the previous ones, and a few draws per wager cost nothing.
 */
public final class CasinoRandom implements RandomSource {

    /** Thread-safe and self-seeding; one for the whole server. */
    private static final SecureRandom SECURE = new SecureRandom();

    public static final CasinoRandom INSTANCE = new CasinoRandom();

    private CasinoRandom() {}

    @Override
    public RandomSource fork() {
        return new XoroshiroRandomSource(SECURE.nextLong(), SECURE.nextLong());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return new XoroshiroRandomSource(SECURE.nextLong(), SECURE.nextLong()).forkPositional();
    }

    /** Ignored: an outcome that a caller could reseed would not be random. */
    @Override
    public void setSeed(long seed) {}

    @Override
    public int nextInt() { return SECURE.nextInt(); }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        return SECURE.nextInt(bound);
    }

    @Override
    public long nextLong() { return SECURE.nextLong(); }

    @Override
    public boolean nextBoolean() { return SECURE.nextBoolean(); }

    @Override
    public float nextFloat() { return SECURE.nextFloat(); }

    @Override
    public double nextDouble() { return SECURE.nextDouble(); }

    @Override
    public double nextGaussian() { return SECURE.nextGaussian(); }
}
