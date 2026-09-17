package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Asks for this player's own casino stats. Carries nothing: the server answers for the sender and
 * nobody else, so there is no field a modified client could point at another player.
 */
public record C2SRequestStats() implements CustomPacketPayload {

    public static final C2SRequestStats INSTANCE = new C2SRequestStats();

    public static final CustomPacketPayload.Type<C2SRequestStats> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("request_stats"));

    public static final StreamCodec<ByteBuf, C2SRequestStats> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
