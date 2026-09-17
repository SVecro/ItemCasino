package com.itemcasino.client;

import com.itemcasino.ItemCasino;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.screen.StatsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.RecipeBookType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * A small gold coin in the corner of the survival inventory that opens the casino stats.
 *
 * <p>Kept deliberately light: one 11-pixel button above the crafting grid, no new keybind, no
 * overlay. The recipe book moves the inventory sideways when it opens, so the button is re-placed
 * every frame from the screen's own position rather than fixed at init, and it hides itself when a
 * narrow window lets the recipe book cover the inventory completely.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID, value = Dist.CLIENT)
public final class InventoryStatsButton {

    private static final int SIZE = 11;
    /** Panel-relative position: the top-right corner of the vanilla inventory, clear of its labels. */
    private static final int OFFSET_X = 176 - SIZE - 6;
    private static final int OFFSET_Y = 5;

    private InventoryStatsButton() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) return;
        CoinButton button = new CoinButton(screen.getGuiLeft() + OFFSET_X, screen.getGuiTop() + OFFSET_Y,
                b -> Minecraft.getInstance().setScreen(new StatsScreen(screen)));
        button.setTooltip(Tooltip.create(Component.translatable("itemcasino.stats.button")));
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) return;
        Minecraft minecraft = Minecraft.getInstance();
        boolean covered = screen.width < 379 && minecraft.player != null
                && minecraft.player.getRecipeBook().isOpen(RecipeBookType.CRAFTING);
        for (var child : screen.children()) {
            if (child instanceof CoinButton button) {
                button.setPosition(screen.getGuiLeft() + OFFSET_X, screen.getGuiTop() + OFFSET_Y);
                button.visible = !covered;
            }
        }
    }

    /** A drawn coin rather than a sprite: nothing to ship in a resource pack. */
    private static final class CoinButton extends Button {

        CoinButton(int x, int y, OnPress onPress) {
            super(x, y, SIZE, SIZE, Component.translatable("itemcasino.stats.button"), onPress,
                    DEFAULT_NARRATION);
        }

        @Override
        protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            boolean hot = isHoveredOrFocused();
            CasinoPanel.rounded(g, x, y, SIZE, SIZE, CasinoPanel.GOLD_DEEP);
            CasinoPanel.rounded(g, x + 1, y + 1, SIZE - 2, SIZE - 2, hot ? 0xFFF0D270 : CasinoPanel.GOLD);
            var font = Minecraft.getInstance().font;
            String glyph = "$";
            g.drawString(font, glyph, x + (SIZE - font.width(glyph)) / 2 + 1, y + 2, 0xFF5A3F0C, false);
        }
    }
}
