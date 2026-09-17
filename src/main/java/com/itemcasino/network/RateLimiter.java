package com.itemcasino.network;

import com.itemcasino.CasinoConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-player token bucket over casino packets.
 *
 * <p>Pricing a stack is cheap but not free, and {@code C2SSelectTarget} does it on every keystroke
 * of a fast client. Twenty packets a second is far more than a human can produce and far less than
 * a script needs to matter.
 */
public final class RateLimiter {

    /** How long refusals are counted before the count starts over. */
    private static final long WINDOW_NANOS = 5_000_000_000L;
    /** Refusals in one window, per packet of budget, that make a burst into a flood: 10 x 20 = 200. */
    private static final int FLOOD_FACTOR = 10;

    private static final Map<UUID, Bucket> BUCKETS = new ConcurrentHashMap<>();

    private RateLimiter() {}

    /** @return false when the player is over budget; the caller should drop the packet. */
    public static boolean allow(ServerPlayer player) {
        int perSecond = CasinoConfig.SERVER.packetsPerSecond.get();
        long now = System.nanoTime();
        Bucket bucket = BUCKETS.computeIfAbsent(player.getUUID(), id -> new Bucket(now, perSecond));
        return bucket.tryConsume(now, perSecond);
    }

    /**
     * Sustained abuse, as opposed to a momentary burst. Worth disconnecting over.
     *
     * <p>Refusals are counted over a five-second window and not reset by the packets that do get
     * through. Resetting them on every accepted packet meant the budget itself, which lets twenty
     * through each second, cleared the count twenty times a second: a client had to send thousands of
     * packets a second before this could ever answer yes.
     */
    public static boolean isFlooding(ServerPlayer player) {
        Bucket bucket = BUCKETS.get(player.getUUID());
        return bucket != null && bucket.floodedWith(CasinoConfig.SERVER.packetsPerSecond.get() * FLOOD_FACTOR);
    }

    public static void forget(ServerPlayer player) {
        BUCKETS.remove(player.getUUID());
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos;
        private long windowStart;
        private int rejections;

        Bucket(long now, int capacity) {
            this.tokens = capacity;
            this.lastNanos = now;
            this.windowStart = now;
        }

        synchronized boolean tryConsume(long now, int perSecond) {
            double elapsed = (now - lastNanos) / 1_000_000_000.0D;
            lastNanos = now;
            tokens = Math.min(perSecond, tokens + elapsed * perSecond);
            if (now - windowStart > WINDOW_NANOS) {
                windowStart = now;
                rejections = 0;
            }
            if (tokens >= 1.0D) {
                tokens -= 1.0D;
                return true;
            }
            rejections++;
            return false;
        }

        synchronized boolean floodedWith(int limit) {
            return rejections > limit;
        }
    }
}
