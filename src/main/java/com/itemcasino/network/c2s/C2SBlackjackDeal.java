package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Deal the opening four cards. Legal only once, from LOCKED. */
public record C2SBlackjackDeal(int containerId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SBlackjackDeal> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("blackjack_deal"));

    public static final StreamCodec<ByteBuf, C2SBlackjackDeal> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SBlackjackDeal::containerId,
            C2SBlackjackDeal::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
