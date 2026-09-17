package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import com.itemcasino.player.CasinoStats;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A player's casino stats, as the server recorded them. Display only: every number here was
 * computed at settle time on the server, and the client never adds anything up itself.
 *
 * @param perGame      wagers per {@code S2CSessionStarted.GAME_*} id
 * @param pendingItems items the mailbox is keeping for this player
 * @param potValue     what the shared jackpot is worth right now, micro-units
 */
public record S2CStats(long wagers, long wins, long losses, long pushes, long staked, long returned,
                       long biggestWin, long jackpots, long jackpotValue, long[] perGame,
                       long pendingItems, long potValue) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CStats> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("stats"));

    public static final StreamCodec<FriendlyByteBuf, S2CStats> CODEC =
            StreamCodec.ofMember(S2CStats::write, S2CStats::read);

    public static S2CStats of(CasinoStats.Entry e, long pendingItems, long potValue) {
        return new S2CStats(e.wagers, e.wins, e.losses, e.pushes, e.staked, e.returned,
                e.biggestWin, e.jackpots, e.jackpotValue, e.perGame.clone(), pendingItems, potValue);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarLong(wagers);
        buf.writeVarLong(wins);
        buf.writeVarLong(losses);
        buf.writeVarLong(pushes);
        buf.writeVarLong(staked);
        buf.writeVarLong(returned);
        buf.writeVarLong(biggestWin);
        buf.writeVarLong(jackpots);
        buf.writeVarLong(jackpotValue);
        buf.writeVarInt(perGame.length);
        for (long v : perGame) buf.writeVarLong(v);
        buf.writeVarLong(pendingItems);
        buf.writeVarLong(potValue);
    }

    private static S2CStats read(FriendlyByteBuf buf) {
        long wagers = buf.readVarLong(), wins = buf.readVarLong(), losses = buf.readVarLong();
        long pushes = buf.readVarLong(), staked = buf.readVarLong(), returned = buf.readVarLong();
        long biggest = buf.readVarLong(), jackpots = buf.readVarLong(), jackpotValue = buf.readVarLong();
        int games = buf.readVarInt();
        // The cap is what keeps a crafted packet from allocating an arbitrary array.
        if (games < 0 || games > 32) throw new io.netty.handler.codec.DecoderException("games " + games);
        long[] perGame = new long[games];
        for (int g = 0; g < games; g++) perGame[g] = buf.readVarLong();
        return new S2CStats(wagers, wins, losses, pushes, staked, returned, biggest, jackpots,
                jackpotValue, perGame, buf.readVarLong(), buf.readVarLong());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
