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

    private static final Map<UUID, Bucket> BUCKETS = new ConcurrentHashMap<>();

    private RateLimiter() {}

    /** @return false when the player is over budget; the caller should drop the packet. */
    public static boolean allow(ServerPlayer player) {
        int perSecond = CasinoConfig.SERVER.packetsPerSecond.get();
        long now = System.nanoTime();
        Bucket bucket = BUCKETS.computeIfAbsent(player.getUUID(), id -> new Bucket(now, perSecond));
        return bucket.tryConsume(now, perSecond);
    }

    /** Sustained abuse, as opposed to a momentary burst. Worth disconnecting over. */
    public static boolean isFlooding(ServerPlayer player) {
        Bucket bucket = BUCKETS.get(player.getUUID());
        return bucket != null && bucket.rejections > 200;
    }

    public static void forget(ServerPlayer player) {
        BUCKETS.remove(player.getUUID());
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos;
        private int rejections;

        Bucket(long now, int capacity) {
            this.tokens = capacity;
            this.lastNanos = now;
        }

        synchronized boolean tryConsume(long now, int perSecond) {
            double elapsed = (now - lastNanos) / 1_000_000_000.0D;
            lastNanos = now;
            tokens = Math.min(perSecond, tokens + elapsed * perSecond);
            if (tokens >= 1.0D) {
                tokens -= 1.0D;
                rejections = 0;
                return true;
            }
            rejections++;
            return false;
        }
    }
}
