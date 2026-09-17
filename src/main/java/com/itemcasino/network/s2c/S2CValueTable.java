package com.itemcasino.network.s2c;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * The <strong>advisory</strong> value table, sent once on join and after every {@code /reload}.
 *
 * <p>It exists so the target picker can list and sort a thousand items without a round trip each.
 * It is never consulted for anything that decides an outcome: the odds on the confirm button come
 * from {@code S2COddsQuote}, and the roll happens on the server. Only targetable items are sent, so
 * membership in this list doubles as "this item may be chosen".
 *
 * <p>About 1 200 entries on vanilla, roughly 8 KB on the wire.
 */
public record S2CValueTable(List<Entry> entries) implements CustomPacketPayload {

    /** @param itemId numeric registry id; @param value micro-units */
    public record Entry(int itemId, long value) {
        public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Entry::itemId,
                ByteBufCodecs.VAR_LONG, Entry::value,
                Entry::new);
    }

    public static final CustomPacketPayload.Type<S2CValueTable> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("value_table"));

    public static final StreamCodec<ByteBuf, S2CValueTable> CODEC = StreamCodec.composite(
            Entry.CODEC.apply(ByteBufCodecs.list(65_536)), S2CValueTable::entries,
            S2CValueTable::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
