package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The winnings are sitting in the table waiting to be claimed (empty list on a loss).
 *
 * <p>Uses {@link RegistryFriendlyByteBuf} because item stacks carry registry-backed data
 * components, and a hard list cap because an unbounded list decoder is a trivial OOM vector.
 */
public record S2CPayoutReady(int containerId, long sessionId, List<ItemStack> payout,
                             byte winnerSeat, byte tier, long winCents)
        implements CustomPacketPayload {

    /** Nothing won and something given back: a push, a refund, a pair that returns the stake. */
    public static final byte TIER_RETURN = -1;
    /** Nothing won, nothing back. */
    public static final byte TIER_NONE = 0;
    public static final byte TIER_WIN = 1;
    /** Ten times the stake or more. */
    public static final byte TIER_BIG = 2;
    /** The Vault's pot. */
    public static final byte TIER_JACKPOT = 3;

    public static final CustomPacketPayload.Type<S2CPayoutReady> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("payout_ready"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CPayoutReady> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, S2CPayoutReady::containerId,
                    ByteBufCodecs.VAR_LONG, S2CPayoutReady::sessionId,
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(
                            com.itemcasino.valuation.PayoutResolver.SUMMARY_LIMIT)),
                    S2CPayoutReady::payout,
                    ByteBufCodecs.BYTE, S2CPayoutReady::winnerSeat,
                    ByteBufCodecs.BYTE, S2CPayoutReady::tier,
                    ByteBufCodecs.VAR_LONG, S2CPayoutReady::winCents,
                    S2CPayoutReady::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
