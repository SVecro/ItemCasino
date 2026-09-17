package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The seated player's chip bet, in whole chips. The server caps it by the table and by the card. */
public record C2SSetBet(int containerId, long chips) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SSetBet> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("set_bet"));

    public static final StreamCodec<ByteBuf, C2SSetBet> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SSetBet::containerId,
            ByteBufCodecs.VAR_LONG, C2SSetBet::chips,
            C2SSetBet::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
