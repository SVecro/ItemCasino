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
 * One packet per phase change: every chair's hand, the dealer's up-card, and whose turn it is.
 *
 * <p>{@code dealerVisible} contains only the cards the players are entitled to see. The hole card is
 * not sent "hidden" for the client to politely not draw — it is simply absent from the packet until
 * the reveal. Trusting a client with a face-down card is the oldest mistake in card-game networking.
 *
 * <p>The players' hands are another matter: at a real table they are face up, and a chair that could
 * not see its neighbours would not be a table at all. {@code legalMask} is still the recipient's
 * own, so seeing another chair's cards never turns into acting for it.
 */
public record S2CBlackjackState(int containerId, long sessionId, byte phase,
                                List<SeatHand> hands, List<Card> dealerVisible,
                                boolean holeHidden, int legalMask, int turn,
                                int dealerTotal, int deadlineTicks)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<S2CBlackjackState> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("blackjack_state"));

    public static final StreamCodec<ByteBuf, S2CBlackjackState> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, S2CBlackjackState::containerId,
            ByteBufCodecs.VAR_LONG, S2CBlackjackState::sessionId,
            ByteBufCodecs.BYTE, S2CBlackjackState::phase,
            SeatHand.LIST, S2CBlackjackState::hands,
            CasinoCodecs.CARDS, S2CBlackjackState::dealerVisible,
            ByteBufCodecs.BOOL, S2CBlackjackState::holeHidden,
            ByteBufCodecs.VAR_INT, S2CBlackjackState::legalMask,
            ByteBufCodecs.VAR_INT, S2CBlackjackState::turn,
            ByteBufCodecs.VAR_INT, S2CBlackjackState::dealerTotal,
            ByteBufCodecs.VAR_INT, S2CBlackjackState::deadlineTicks,
            S2CBlackjackState::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
