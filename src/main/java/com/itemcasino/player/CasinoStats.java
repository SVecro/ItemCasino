package com.itemcasino.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What each player has done at the casino, recorded by the server as it settles.
 *
 * <p>Kept in world data keyed by UUID rather than on the player entity: a hand can settle on its
 * deadline after the player has logged out, and an entity that is not loaded cannot be written to.
 * Values are in the valuation engine's micro-units, measured against the snapshot each wager was
 * locked with, so a later {@code /reload} does not rewrite history.
 */
public class CasinoStats extends SavedData {

    private static final String FILE_ID = "itemcasino_stats";

    /** One slot per {@code S2CSessionStarted.GAME_*} id. */
    public static final int GAMES = 7;

    /** A player's running totals. Mutable on purpose: it is updated in place on every settle. */
    public static final class Entry {
        public long wagers;
        public long wins;
        public long losses;
        public long pushes;
        public long staked;
        public long returned;
        public long biggestWin;
        public long jackpots;
        public long jackpotValue;
        public final long[] perGame = new long[GAMES];

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.optionalFieldOf("wagers", 0L).forGetter(e -> e.wagers),
                Codec.LONG.optionalFieldOf("wins", 0L).forGetter(e -> e.wins),
                Codec.LONG.optionalFieldOf("losses", 0L).forGetter(e -> e.losses),
                Codec.LONG.optionalFieldOf("pushes", 0L).forGetter(e -> e.pushes),
                Codec.LONG.optionalFieldOf("staked", 0L).forGetter(e -> e.staked),
                Codec.LONG.optionalFieldOf("returned", 0L).forGetter(e -> e.returned),
                Codec.LONG.optionalFieldOf("biggest_win", 0L).forGetter(e -> e.biggestWin),
                Codec.LONG.optionalFieldOf("jackpots", 0L).forGetter(e -> e.jackpots),
                Codec.LONG.optionalFieldOf("jackpot_value", 0L).forGetter(e -> e.jackpotValue),
                Codec.LONG.listOf().optionalFieldOf("per_game", List.of()).forGetter(e -> {
                    List<Long> out = new ArrayList<>(GAMES);
                    for (long v : e.perGame) out.add(v);
                    return out;
                })
        ).apply(i, (wagers, wins, losses, pushes, staked, returned, biggest, jackpots, jackpotValue, perGame) -> {
            Entry e = new Entry();
            e.wagers = wagers; e.wins = wins; e.losses = losses; e.pushes = pushes;
            e.staked = staked; e.returned = returned; e.biggestWin = biggest;
            e.jackpots = jackpots; e.jackpotValue = jackpotValue;
            for (int g = 0; g < Math.min(GAMES, perGame.size()); g++) e.perGame[g] = perGame.get(g);
            return e;
        }));
    }

    private record Row(UUID player, Entry entry) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(Row::player),
                Entry.CODEC.fieldOf("stats").forGetter(Row::entry)
        ).apply(i, Row::new));
    }

    public static final Codec<CasinoStats> CODEC = Row.CODEC.listOf()
            .xmap(CasinoStats::new, CasinoStats::rows);

    public static final SavedDataType<CasinoStats> TYPE =
            new SavedDataType<>(FILE_ID, CasinoStats::new, CODEC);

    private final Map<UUID, Entry> entries = new HashMap<>();

    public CasinoStats() {}

    private CasinoStats(List<Row> rows) {
        for (Row row : rows) entries.put(row.player(), row.entry());
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>(entries.size());
        entries.forEach((id, entry) -> out.add(new Row(id, entry)));
        return out;
    }

    public static CasinoStats of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** A copy-free read; an unknown player gets an empty entry that is not stored. */
    public Entry get(UUID player) {
        Entry entry = entries.get(player);
        return entry != null ? entry : new Entry();
    }

    // ------------------------------------------------------------------ recording

    /**
     * One settled wager.
     *
     * @param game     the {@code S2CSessionStarted.GAME_*} id
     * @param staked   what was put at risk, micro-units
     * @param returned what came back, micro-units (the whole payout, stake included)
     */
    public static void recordWager(@Nullable MinecraftServer server, @Nullable UUID player, int game,
                                   long staked, long returned) {
        if (server == null || player == null) return;
        CasinoStats stats = of(server);
        Entry e = stats.entries.computeIfAbsent(player, id -> new Entry());
        staked = Math.max(0, staked);
        returned = Math.max(0, returned);
        e.wagers++;
        if (game >= 0 && game < GAMES) e.perGame[game]++;
        e.staked = saturatingAdd(e.staked, staked);
        e.returned = saturatingAdd(e.returned, returned);
        if (returned > staked) {
            e.wins++;
            e.biggestWin = Math.max(e.biggestWin, returned - staked);
        } else if (returned == staked) {
            e.pushes++;
        } else {
            e.losses++;
        }
        stats.setDirty();
    }

    public static void recordJackpot(@Nullable MinecraftServer server, @Nullable UUID player, long value) {
        if (server == null || player == null) return;
        CasinoStats stats = of(server);
        Entry e = stats.entries.computeIfAbsent(player, id -> new Entry());
        e.jackpots++;
        e.jackpotValue = saturatingAdd(e.jackpotValue, Math.max(0, value));
        stats.setDirty();
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
