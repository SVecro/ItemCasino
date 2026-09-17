package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A session has locked. The client opens its animation state and stops accepting slot input. */
public record S2CSessionStarted(int containerId, long sessionId, byte gameType, int oddsPpm)
        implements CustomPacketPayload {

    public static final byte GAME_UPGRADER = 0;
    public static final byte GAME_DICE = 1;
    public static final byte GAME_BLACKJACK = 2;
    public static final byte GAME_COIN_FLIP = 3;
    public static final byte GAME_SLOT_MACHINE = 4;
    public static final byte GAME_VAULT = 5;
    public static final byte GAME_MINE_FIELD = 6;

    public static final CustomPacketPayload.Type<S2CSessionStarted> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("session_started"));

    public static final StreamCodec<ByteBuf, S2CSessionStarted> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CSessionStarted::containerId,
            ByteBufCodecs.VAR_LONG, S2CSessionStarted::sessionId,
            ByteBufCodecs.BYTE, S2CSessionStarted::gameType,
            ByteBufCodecs.VAR_INT, S2CSessionStarted::oddsPpm,
            S2CSessionStarted::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
