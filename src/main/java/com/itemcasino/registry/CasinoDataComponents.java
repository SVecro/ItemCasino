package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoDataComponents {

    public static final DeferredRegister.DataComponents REGISTER =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ItemCasino.MOD_ID);

    /**
     * A chip card's balance, in hundredths of a chip. Persistent, so it survives in chests and
     * inventories like any other item data; synchronised, so the tooltip and the bet bar can read it
     * on the client. The client never writes it: every change goes through a server-side table or
     * the cashier.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> CHIPS =
            REGISTER.registerComponentType("chips", builder -> builder
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG));

    private CasinoDataComponents() {}
}
