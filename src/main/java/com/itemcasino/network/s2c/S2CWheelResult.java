package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The entire wheel animation, decided in advance.
 *
 * <p>{@code stopAngle} was chosen inside the arc that matches {@code win}, so the picture and the
 * ledger cannot disagree and a client that skips the animation only saves itself three seconds.
 */
public record S2CWheelResult(int containerId, long sessionId, boolean win,
                             float stopAngle, int spinTicks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CWheelResult> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("wheel_result"));

    public static final StreamCodec<ByteBuf, S2CWheelResult> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CWheelResult::containerId,
            ByteBufCodecs.VAR_LONG, S2CWheelResult::sessionId,
            ByteBufCodecs.BOOL, S2CWheelResult::win,
            ByteBufCodecs.FLOAT, S2CWheelResult::stopAngle,
            ByteBufCodecs.VAR_INT, S2CWheelResult::spinTicks,
            S2CWheelResult::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
