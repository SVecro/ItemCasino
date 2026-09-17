package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sets the game's own setting — the dice bet, the upgrader's count, the Vault's share, the mines.
 * An int since the dice bet packs a chance and a direction into one value; the server clamps and
 * validates whatever arrives.
 *
 * <p>It goes through the server rather than staying a client preference because these tables are
 * shared: everyone crowded around one must see the same coin turning. The server still decides the
 * outcome; this only sets the bet the player is making.
 */
public record C2SSetOption(int containerId, int option) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<C2SSetOption> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("set_option"));

    public static final StreamCodec<ByteBuf, C2SSetOption> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, C2SSetOption::containerId,
            ByteBufCodecs.VAR_INT, C2SSetOption::option,
            C2SSetOption::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
