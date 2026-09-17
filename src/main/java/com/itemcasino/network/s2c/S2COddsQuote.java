package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The authoritative odds for the confirm button.
 *
 * <p>The client has a cached value table for sorting the target list, but the number rendered next
 * to SPIN always comes from here. Values are in micro-units, or {@code -1} for "unpriced".
 */
public record S2COddsQuote(int containerId, Identifier target, int oddsPpm,
                           long inputValue, long targetValue) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2COddsQuote> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("odds_quote"));

    public static final StreamCodec<ByteBuf, S2COddsQuote> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2COddsQuote::containerId,
            Identifier.STREAM_CODEC, S2COddsQuote::target,
            ByteBufCodecs.VAR_INT, S2COddsQuote::oddsPpm,
            ByteBufCodecs.LONG, S2COddsQuote::inputValue,
            ByteBufCodecs.LONG, S2COddsQuote::targetValue,
            S2COddsQuote::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
