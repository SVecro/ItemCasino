package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Three faces, already decided, and the timing to reveal them with.
 *
 * <p>No multiplier on the wire. The client reads the paytable itself, out of the same
 * dependency-free class the server used, so the number on screen cannot drift from the number in
 * the ledger — there is only one paytable and both sides read it.
 */
public record S2CSlotResult(int containerId, long sessionId, byte left, byte middle, byte right,
                            int spinTicks, int stagger) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CSlotResult> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("slot_result"));

    public static final StreamCodec<ByteBuf, S2CSlotResult> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CSlotResult::containerId,
            ByteBufCodecs.VAR_LONG, S2CSlotResult::sessionId,
            ByteBufCodecs.BYTE, S2CSlotResult::left,
            ByteBufCodecs.BYTE, S2CSlotResult::middle,
            ByteBufCodecs.BYTE, S2CSlotResult::right,
            ByteBufCodecs.VAR_INT, S2CSlotResult::spinTicks,
            ByteBufCodecs.VAR_INT, S2CSlotResult::stagger,
            S2CSlotResult::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
