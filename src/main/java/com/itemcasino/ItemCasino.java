package com.itemcasino;

import com.itemcasino.gametest.CasinoTestFunctions;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.registry.CasinoBlocks;
import com.itemcasino.registry.CasinoEntities;
import com.itemcasino.registry.CasinoCreativeTab;
import com.itemcasino.registry.CasinoDataComponents;
import com.itemcasino.registry.CasinoDataMaps;
import com.itemcasino.registry.CasinoItems;
import com.itemcasino.registry.CasinoMenus;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point. Nothing here may reference {@code net.minecraft.client}; the client half
 * lives in {@link com.itemcasino.client.ItemCasinoClient}, which is annotated
 * {@code dist = Dist.CLIENT} and therefore never loads on a dedicated server.
 */
@Mod(ItemCasino.MOD_ID)
public final class ItemCasino {

    public static final String MOD_ID = "itemcasino";
    public static final Logger LOGGER = LoggerFactory.getLogger("ItemCasino");

    /**
     * A mob with no attribute map cannot be constructed at all, and the failure arrives as a null
     * pointer deep in the entity's constructor rather than as anything that names the cause.
     */
    private static void onEntityAttributes(
            net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(CasinoEntities.GOBLIN.get(),
                com.itemcasino.entity.GamblerGoblin.attributes().build());
    }

    public ItemCasino(IEventBus modBus, ModContainer container) {
        CasinoBlocks.REGISTER.register(modBus);
        CasinoDataComponents.REGISTER.register(modBus);
        CasinoItems.REGISTER.register(modBus);
        CasinoBlockEntities.REGISTER.register(modBus);
        CasinoMenus.REGISTER.register(modBus);
        CasinoEntities.REGISTER.register(modBus);
        CasinoCreativeTab.REGISTER.register(modBus);
        // Registered unconditionally: the registry exists on every side, and the tests only run
        // when neoforge.enabledGameTestNamespaces names this mod.
        CasinoTestFunctions.REGISTER.register(modBus);

        modBus.addListener(CasinoDataMaps::onRegisterDataMaps);
        // Explicitly, not by annotation: @EventBusSubscriber has no bus selector any more,
        // and this one has to land on the mod bus rather than the game bus.
        modBus.addListener(ItemCasino::onEntityAttributes);

        // SERVER config holds everything a client must not be able to influence (odds, rules,
        // base values). COMMON holds constants that datagen and unit tests also need.
        container.registerConfig(ModConfig.Type.SERVER, CasinoConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, CasinoConfig.COMMON_SPEC);

        // Game-bus handlers (ValuationEngine, CasinoCommands, CasinoEvents) register themselves
        // through @EventBusSubscriber; registering them here as well would double-fire everything.
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
