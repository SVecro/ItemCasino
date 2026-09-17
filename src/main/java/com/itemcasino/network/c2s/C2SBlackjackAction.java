package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Hit, stand, double or surrender.
 *
 * <p>The client greys out illegal buttons using the mask the server sent, but the server re-derives
 * legality from its own table on receipt: the mask is a courtesy, not an authorisation.
 */
public record C2SBlackjackAction(int containerId, long sessionId, byte action)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SBlackjackAction> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("blackjack_action"));

    public static final StreamCodec<ByteBuf, C2SBlackjackAction> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SBlackjackAction::containerId,
            ByteBufCodecs.VAR_LONG, C2SBlackjackAction::sessionId,
            ByteBufCodecs.BYTE, C2SBlackjackAction::action,
            C2SBlackjackAction::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
