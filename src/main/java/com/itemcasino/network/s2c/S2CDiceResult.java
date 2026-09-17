package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A dice roll, already decided: what came up, against which bet. The client only animates towards
 * it; the payout was worked out on the server from the same numbers.
 *
 * @param roll   0..9999, hundredths
 * @param chance the bet's chance in hundredths of a percent
 */
public record S2CDiceResult(int containerId, long sessionId, int roll, boolean win, boolean over,
                            int chance, int rollTicks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CDiceResult> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("dice_result"));

    public static final StreamCodec<ByteBuf, S2CDiceResult> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CDiceResult::containerId,
            ByteBufCodecs.VAR_LONG, S2CDiceResult::sessionId,
            ByteBufCodecs.VAR_INT, S2CDiceResult::roll,
            ByteBufCodecs.BOOL, S2CDiceResult::win,
            ByteBufCodecs.BOOL, S2CDiceResult::over,
            ByteBufCodecs.VAR_INT, S2CDiceResult::chance,
            ByteBufCodecs.VAR_INT, S2CDiceResult::rollTicks,
            S2CDiceResult::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
