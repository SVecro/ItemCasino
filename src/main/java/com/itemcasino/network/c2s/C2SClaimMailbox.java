package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Collect whatever the casino is keeping for the sender, as far as their inventory allows. */
public record C2SClaimMailbox() implements CustomPacketPayload {

    public static final C2SClaimMailbox INSTANCE = new C2SClaimMailbox();

    public static final CustomPacketPayload.Type<C2SClaimMailbox> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("claim_mailbox"));

    public static final StreamCodec<ByteBuf, C2SClaimMailbox> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
