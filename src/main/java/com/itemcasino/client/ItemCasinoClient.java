package com.itemcasino.client;

import com.itemcasino.ItemCasino;
import com.itemcasino.client.screen.BlackjackScreen;
import com.itemcasino.client.screen.CoinFlipScreen;
import com.itemcasino.client.screen.DiceScreen;
import com.itemcasino.client.screen.MineFieldScreen;
import com.itemcasino.client.screen.CashierScreen;
import com.itemcasino.client.screen.SlotMachineScreen;
import com.itemcasino.client.screen.VaultScreen;
import com.itemcasino.client.screen.UpgraderScreen;
import com.itemcasino.client.render.GoblinRenderer;
import com.itemcasino.registry.CasinoEntities;
import com.itemcasino.registry.CasinoMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * The client half of the mod. Annotated {@code dist = Dist.CLIENT}, so a dedicated server never
 * loads this class or anything it touches.
 */
@Mod(value = ItemCasino.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ItemCasino.MOD_ID, value = Dist.CLIENT)
public final class ItemCasinoClient {

    public ItemCasinoClient() {}

    /**
     * Everything the client caches is scoped to one connection: the value table comes from that
     * server's datapacks and the session state from that server's tables. Leaving them behind
     * would show the next server's players stale prices.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientValueCache.clear();
        ClientSessionState.reset();
        ClientBlackjackState.reset();
        ClientSlotState.reset();
        ClientStatsState.reset();
    }

    /** A chip card shows what it holds. The balance lives on the stack, so this is only a reading of it. */
    @SubscribeEvent
    public static void onItemTooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
        if (!com.itemcasino.chips.ChipCards.isCard(event.getItemStack())) return;
        long cents = com.itemcasino.chips.ChipCards.balance(event.getItemStack());
        event.getToolTip().add(net.minecraft.network.chat.Component.translatable("itemcasino.card.balance",
                com.itemcasino.client.render.ChipText.format(cents)).withStyle(net.minecraft.ChatFormatting.GOLD));
        event.getToolTip().add(net.minecraft.network.chat.Component.translatable("itemcasino.card.bearer")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    @SubscribeEvent
    public static void onRegisterLayers(
            net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(GoblinRenderer.LAYER, GoblinRenderer::createLayer);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(
            net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CasinoEntities.GOBLIN.get(), GoblinRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(CasinoMenus.UPGRADER.get(), UpgraderScreen::new);
        event.register(CasinoMenus.DICE.get(), DiceScreen::new);
        event.register(CasinoMenus.BLACKJACK_TABLE.get(), BlackjackScreen::new);
        event.register(CasinoMenus.COIN_FLIP.get(), CoinFlipScreen::new);
        event.register(CasinoMenus.SLOT_MACHINE.get(), SlotMachineScreen::new);
        event.register(CasinoMenus.VAULT.get(), VaultScreen::new);
        event.register(CasinoMenus.MINE_FIELD.get(), MineFieldScreen::new);
        event.register(CasinoMenus.CASHIER.get(), CashierScreen::new);
    }
}
