package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Advisory: the client has finished animating. The server also settles on a deadline, so a client that never sends this -- crashed, lagging, or stalling on purpose -- changes nothing but the timing. */
public record C2SAnimationComplete(int containerId, long sessionId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SAnimationComplete> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("animation_complete"));

    public static final StreamCodec<ByteBuf, C2SAnimationComplete> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SAnimationComplete::containerId,
            ByteBufCodecs.VAR_LONG, C2SAnimationComplete::sessionId,
            C2SAnimationComplete::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
