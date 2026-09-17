package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A draw at the Vault, already decided and, if it won, already paid. */
public record S2CVaultDraw(int containerId, long sessionId, boolean won, int drawPpm,
                           int spinTicks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CVaultDraw> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("vault_draw"));

    public static final StreamCodec<ByteBuf, S2CVaultDraw> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CVaultDraw::containerId,
            ByteBufCodecs.VAR_LONG, S2CVaultDraw::sessionId,
            ByteBufCodecs.BOOL, S2CVaultDraw::won,
            ByteBufCodecs.VAR_INT, S2CVaultDraw::drawPpm,
            ByteBufCodecs.VAR_INT, S2CVaultDraw::spinTicks,
            S2CVaultDraw::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
