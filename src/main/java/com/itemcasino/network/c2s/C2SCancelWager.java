package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Clear the chosen target while still in ARMED. Nothing is escrowed yet, so nothing is refunded. */
public record C2SCancelWager(int containerId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SCancelWager> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("cancel_wager"));

    public static final StreamCodec<ByteBuf, C2SCancelWager> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SCancelWager::containerId,
            C2SCancelWager::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
