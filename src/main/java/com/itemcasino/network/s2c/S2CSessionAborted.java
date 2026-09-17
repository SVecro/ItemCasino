package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A session was torn down server-side; the client should stop animating and re-enable its slots. */
public record S2CSessionAborted(int containerId, long sessionId, byte reason)
        implements CustomPacketPayload {

    public static final byte REASON_BLOCK_BROKEN = 0;
    public static final byte REASON_TIMEOUT = 1;
    public static final byte REASON_RELOAD = 2;
    public static final byte REASON_ERROR = 3;

    public static final CustomPacketPayload.Type<S2CSessionAborted> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("session_aborted"));

    public static final StreamCodec<ByteBuf, S2CSessionAborted> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CSessionAborted::containerId,
            ByteBufCodecs.VAR_LONG, S2CSessionAborted::sessionId,
            ByteBufCodecs.BYTE, S2CSessionAborted::reason,
            S2CSessionAborted::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
