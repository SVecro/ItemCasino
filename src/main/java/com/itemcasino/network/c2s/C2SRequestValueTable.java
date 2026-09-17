package com.itemcasino.network.c2s;

import com.itemcasino.ItemCasino;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Ask for the advisory value table again (after a resource reload on the client, say). */
public record C2SRequestValueTable() implements CustomPacketPayload {

    public static final C2SRequestValueTable INSTANCE = new C2SRequestValueTable();

    public static final CustomPacketPayload.Type<C2SRequestValueTable> TYPE =
            new CustomPacketPayload.Type<>(ItemCasino.id("request_value_table"));

    public static final StreamCodec<ByteBuf, C2SRequestValueTable> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
