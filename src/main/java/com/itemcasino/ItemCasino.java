package com.itemcasino;

import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.registry.CasinoBlocks;
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
     * Every wager, settlement, cashier exchange, jackpot and mailbox entry, when
     * {@code safety.log_settlements} is on. A logger of its own so a server can route these lines to a
     * separate file, or quieten them, in its logging configuration without losing the mod's warnings.
     */
    public static final Logger AUDIT = LoggerFactory.getLogger("ItemCasino/Audit");

    public ItemCasino(IEventBus modBus, ModContainer container) {
        CasinoBlocks.REGISTER.register(modBus);
        CasinoDataComponents.REGISTER.register(modBus);
        CasinoItems.REGISTER.register(modBus);
        CasinoBlockEntities.REGISTER.register(modBus);
        CasinoMenus.REGISTER.register(modBus);

        CasinoCreativeTab.REGISTER.register(modBus);
        registerGameTests(modBus);

        // Explicitly, not by annotation: @EventBusSubscriber has no bus selector any more, and this
        // one has to land on the mod bus rather than the game bus.
        modBus.addListener(CasinoDataMaps::onRegisterDataMaps);


        // SERVER config holds everything a client must not be able to influence (odds, rules,
        // base values). COMMON holds constants that datagen and unit tests also need.
        container.registerConfig(ModConfig.Type.SERVER, CasinoConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, CasinoConfig.COMMON_SPEC);

        // Game-bus handlers (ValuationEngine, CasinoCommands, CasinoEvents) register themselves
        // through @EventBusSubscriber; registering them here as well would double-fire everything.
    }

    /**
     * The game tests are a source set of their own (src/gametest) and are not in the released jar,
     * so they are looked up by name: present in a dev run, where they register on every side and
     * only run when neoforge.enabledGameTestNamespaces names this mod; absent for players, where
     * there is nothing to register.
     */
    private static void registerGameTests(IEventBus modBus) {
        Class<?> functions;
        try {
            functions = Class.forName("com.itemcasino.gametest.CasinoTestFunctions");
        } catch (ClassNotFoundException absent) {
            return;
        }
        try {
            functions.getMethod("register", IEventBus.class).invoke(null, modBus);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Item Casino: the game tests are present but could not register", e);
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
