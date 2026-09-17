package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The duel's result, already decided.
 *
 * <p>It carries the winning <em>seat</em> rather than a win flag, because the same packet goes to
 * both duellists and to everyone watching, and "you won" is a different sentence for each of them.
 * Each client compares the seat with its own.
 */
public record S2CCoinFlipResult(int containerId, long sessionId, byte winnerSeat,
                                int flourishSeed, int spinTicks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CCoinFlipResult> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("coin_flip_result"));

    public static final StreamCodec<ByteBuf, S2CCoinFlipResult> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CCoinFlipResult::containerId,
            ByteBufCodecs.VAR_LONG, S2CCoinFlipResult::sessionId,
            ByteBufCodecs.BYTE, S2CCoinFlipResult::winnerSeat,
            ByteBufCodecs.INT, S2CCoinFlipResult::flourishSeed,
            ByteBufCodecs.VAR_INT, S2CCoinFlipResult::spinTicks,
            S2CCoinFlipResult::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
