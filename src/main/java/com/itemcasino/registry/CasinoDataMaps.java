package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.itemcasino.valuation.BaseValue;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

public final class CasinoDataMaps {

    /**
     * Seed values for the valuation engine, authored at
     * {@code data/<namespace>/data_maps/item/base_value.json}.
     *
     * <p>Data maps are the right tool here rather than a bespoke reload listener: they accept tag
     * keys ({@code "#c:raw_materials": {"value": 32}}), they compose across datapacks with
     * {@code replace}/{@code remove}, and they are already loaded and frozen by the time recipes are.
     */
    public static final DataMapType<Item, BaseValue> BASE_VALUE = DataMapType.builder(
            ItemCasino.id("base_value"), Registries.ITEM, BaseValue.CODEC).build();

    public static void onRegisterDataMaps(RegisterDataMapTypesEvent event) {
        event.register(BASE_VALUE);
    }

    private CasinoDataMaps() {}
}
