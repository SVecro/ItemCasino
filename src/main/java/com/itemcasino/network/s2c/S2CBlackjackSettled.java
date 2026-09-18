package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.blackjack.Card;
import com.itemcasino.network.CasinoCodecs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * The end of a hand: every chair's final cards and result, the dealer's whole hand, the totals.
 *
 * <p>Not the payout — that follows once the player has watched the reveal (or its deadline passes),
 * in the same {@link S2CPayoutReady} every other table uses, so nothing about the result reaches the
 * player before the cards do.
 */
public record S2CBlackjackSettled(int containerId, long sessionId, List<SeatHand> hands,
                                  List<Card> dealerFinal, int dealerTotal)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CBlackjackSettled> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("blackjack_settled"));

    public static final StreamCodec<ByteBuf, S2CBlackjackSettled> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, S2CBlackjackSettled::containerId,
                    ByteBufCodecs.VAR_LONG, S2CBlackjackSettled::sessionId,
                    SeatHand.LIST, S2CBlackjackSettled::hands,
                    CasinoCodecs.CARDS, S2CBlackjackSettled::dealerFinal,
                    ByteBufCodecs.VAR_INT, S2CBlackjackSettled::dealerTotal,
                    S2CBlackjackSettled::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
