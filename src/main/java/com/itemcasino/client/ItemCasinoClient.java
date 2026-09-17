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

import com.itemcasino.registry.CasinoItems;
import net.minecraft.client.Minecraft;
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
        net.minecraft.world.item.ItemStack stack = event.getItemStack();
        java.util.List<net.minecraft.network.chat.Component> lines = event.getToolTip();
        if (com.itemcasino.chips.ChipCards.isCard(stack)) {
            long cents = com.itemcasino.chips.ChipCards.balance(stack);
            lines.add(net.minecraft.network.chat.Component.translatable("itemcasino.card.balance",
                    com.itemcasino.client.render.ChipText.format(cents)).withStyle(net.minecraft.ChatFormatting.GOLD));
            lines.add(net.minecraft.network.chat.Component.translatable("itemcasino.card.bearer")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            return;
        }
        String rules = rulesKey(stack);
        if (rules != null) {
            lines.add(net.minecraft.network.chat.Component.translatable(rules)
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        // What the casino would price it at, while Shift is held: the only place outside a table
        // where a player can see the number the tables use. The advisory table lists items that can
        // be Upgrader targets; anything else simply shows nothing.
        if (Minecraft.getInstance().hasShiftDown() && ClientValueCache.isReady()) {
            long value = ClientValueCache.value(stack.getItem());
            if (value != com.itemcasino.core.value.Fixed.INF && value > 0) {
                lines.add(net.minecraft.network.chat.Component.translatable("itemcasino.tooltip.value",
                        com.itemcasino.client.render.ChipText.format(com.itemcasino.core.chips.Chips.centsForValue(value)))
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            }
        }
    }

    /** The one-line description of a casino block or pocket device, or null for anything else. */
    @javax.annotation.Nullable
    private static String rulesKey(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.Item item = stack.getItem();
        if (item == CasinoItems.UPGRADER.get()) return "itemcasino.tooltip.upgrader_edge";
        if (item == CasinoItems.DICE.get()) return "itemcasino.tooltip.dice";
        if (item == CasinoItems.BLACKJACK_TABLE.get()) return "itemcasino.tooltip.blackjack_edge";
        if (item == CasinoItems.COIN_FLIP.get()) return "itemcasino.tooltip.coin_flip_edge";
        if (item == CasinoItems.SLOT_MACHINE.get()) return "itemcasino.tooltip.slot_edge";
        if (item == CasinoItems.VAULT.get()) return "itemcasino.tooltip.vault";
        if (item == CasinoItems.MINE_FIELD.get()) return "itemcasino.tooltip.mine_field";
        if (item == CasinoItems.CASHIER.get()) return "itemcasino.tooltip.cashier";
        if (item == CasinoItems.GAME_CORE.get()) return "itemcasino.tooltip.game_core";
        if (item == CasinoItems.POCKET_UPGRADER.get() || item == CasinoItems.POCKET_DICE.get()
                || item == CasinoItems.POCKET_BLACKJACK.get()) {
            return "itemcasino.tooltip.pocket";
        }
        return null;
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
