package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Deposit what is in the cashier's slot, or withdraw {@code count} of the {@code currency}-th
 * currency. The server prices both at the moment it acts; the numbers the screen showed are only a
 * preview.
 */
public record C2SCashierAction(int containerId, byte action, byte currency, int count)
        implements CustomPacketPayload {

    public static final byte DEPOSIT = 0;
    public static final byte WITHDRAW = 1;

    public static final CustomPacketPayload.Type<C2SCashierAction> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("cashier_action"));

    public static final StreamCodec<ByteBuf, C2SCashierAction> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SCashierAction::containerId,
            ByteBufCodecs.BYTE, C2SCashierAction::action,
            ByteBufCodecs.BYTE, C2SCashierAction::currency,
            ByteBufCodecs.VAR_INT, C2SCashierAction::count,
            C2SCashierAction::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
