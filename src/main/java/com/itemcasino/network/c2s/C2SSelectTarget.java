package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Choose the Upgrader's target item.
 *
 * <p>The payload carries an item id and nothing else — no value, no probability. The server prices
 * both sides itself and answers with {@code S2COddsQuote}; a modified client can pick a target it
 * should not be able to see, and the server will simply refuse it.
 */
public record C2SSelectTarget(int containerId, Identifier target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SSelectTarget> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("select_target"));

    public static final StreamCodec<ByteBuf, C2SSelectTarget> CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, C2SSelectTarget::containerId,
            Identifier.STREAM_CODEC, C2SSelectTarget::target,
            C2SSelectTarget::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
