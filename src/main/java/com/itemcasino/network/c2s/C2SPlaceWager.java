package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Commit the wager currently in the slot. Carries no odds and no outcome: the client asks, the server decides. */
public record C2SPlaceWager(int containerId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SPlaceWager> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("place_wager"));

    public static final StreamCodec<ByteBuf, C2SPlaceWager> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SPlaceWager::containerId,
            C2SPlaceWager::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
