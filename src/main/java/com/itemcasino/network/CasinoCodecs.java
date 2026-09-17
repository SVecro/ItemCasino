package com.itemcasino.network;

import com.itemcasino.core.game.blackjack.Card;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/** Wire codecs shared by several payloads. */
public final class CasinoCodecs {

    /** One byte per card. */
    public static final StreamCodec<ByteBuf, Card> CARD =
            ByteBufCodecs.BYTE.map(Card::unpack, Card::pack);

    /**
     * A hand. The cap matters: an unbounded list decoder is a trivial out-of-memory vector from a
     * crafted packet, and no legal hand comes anywhere near 32 cards.
     */
    public static final StreamCodec<ByteBuf, List<Card>> CARDS = CARD.apply(ByteBufCodecs.list(32));

    private CasinoCodecs() {}
}
