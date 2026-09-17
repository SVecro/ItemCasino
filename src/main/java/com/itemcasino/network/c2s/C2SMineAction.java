package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Turn a tile over, or cash out.
 *
 * <p>{@code token} names the board the click was aimed at, as the menu's data channel showed it; a
 * click that arrives after that board ended is refused rather than landing on the next one. The
 * server decides what the tile was — the client does not know until it is told.
 */
public record C2SMineAction(int containerId, int token, byte action, byte tile)
        implements CustomPacketPayload {

    public static final byte REVEAL = 0;
    public static final byte CASH_OUT = 1;

    public static final CustomPacketPayload.Type<C2SMineAction> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("mine_action"));

    public static final StreamCodec<ByteBuf, C2SMineAction> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SMineAction::containerId,
            ByteBufCodecs.VAR_INT, C2SMineAction::token,
            ByteBufCodecs.BYTE, C2SMineAction::action,
            ByteBufCodecs.BYTE, C2SMineAction::tile,
            C2SMineAction::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
