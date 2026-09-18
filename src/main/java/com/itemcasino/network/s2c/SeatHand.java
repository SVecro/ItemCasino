package com.itemcasino.network.s2c;

import com.itemcasino.core.game.blackjack.Card;
import com.itemcasino.network.CasinoCodecs;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * One chair's hand, as the other chairs are allowed to see it.
 *
 * <p>Sent for every chair playing, so a player watching from across the table sees the cards their
 * neighbours are holding and who is about to act. There is nothing secret in a blackjack hand but
 * the dealer's hole card, which travels separately and is simply absent until the reveal — so
 * sharing every player's cards costs nothing and is what makes the table feel like a table.
 *
 * <p>The name is carried rather than looked up: the client has no map from a chair to a player, and
 * a name is a handful of bytes on a packet that only goes out when the hand changes.
 *
 * @param seat      which chair, 0-based
 * @param cards     the chair's cards
 * @param total     its total, as the server counts it
 * @param name      the player sitting there, for the label under the hand
 * @param outcome   {@code Outcome} ordinal once the hand is settled, -1 while it is still being played
 * @param betUnits  1, or 2 once this chair has doubled
 */
public record SeatHand(int seat, List<Card> cards, int total, String name, int outcome,
                       int betUnits) {

    /** Long enough for any name Minecraft allows, short enough not to be a memory vector. */
    private static final int MAX_NAME = 64;

    public static final StreamCodec<ByteBuf, SeatHand> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SeatHand::seat,
            CasinoCodecs.CARDS, SeatHand::cards,
            ByteBufCodecs.VAR_INT, SeatHand::total,
            ByteBufCodecs.stringUtf8(MAX_NAME), SeatHand::name,
            ByteBufCodecs.VAR_INT, SeatHand::outcome,
            ByteBufCodecs.VAR_INT, SeatHand::betUnits,
            SeatHand::new);

    /** Every chair at a table, capped well above the three any table seats. */
    public static final StreamCodec<ByteBuf, List<SeatHand>> LIST = CODEC.apply(ByteBufCodecs.list(8));
}
