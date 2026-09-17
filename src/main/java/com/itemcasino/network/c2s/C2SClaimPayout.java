package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Pull the winnings out of the table's payout buffer and into the inventory. */
public record C2SClaimPayout(int containerId, long sessionId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SClaimPayout> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("claim_payout"));

    public static final StreamCodec<ByteBuf, C2SClaimPayout> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SClaimPayout::containerId,
            ByteBufCodecs.VAR_LONG, C2SClaimPayout::sessionId,
            C2SClaimPayout::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
