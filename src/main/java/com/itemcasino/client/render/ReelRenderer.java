package com.itemcasino.client.render;

import com.itemcasino.client.ClientSlotState;
import com.itemcasino.core.game.slots.SlotSymbol;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One reel: a window onto a scrolling band of items.
 *
 * <p>The window is clipped with a scissor so a symbol can be half in and half out, which is the
 * whole illusion — without it the band jumps a cell at a time and reads as a slideshow rather than
 * a reel. Three cells are visible and the middle one is the payline.
 */
public final class ReelRenderer {

    public static final int CELL = 20;
    public static final int WIDTH = 28;
    public static final int VISIBLE = 3;
    public static final int HEIGHT = CELL * VISIBLE;

    private ReelRenderer() {}

    /** The item each face wears. Chosen to read as a ladder of worth at a glance. */
    public static Item icon(SlotSymbol symbol) {
        return switch (symbol) {
            case COAL -> Items.COAL;
            case COPPER -> Items.COPPER_INGOT;
            case IRON -> Items.IRON_INGOT;
            case GOLD -> Items.GOLD_INGOT;
            case DIAMOND -> Items.DIAMOND;
            case STAR -> Items.NETHER_STAR;
        };
    }

    public static ItemStack stack(SlotSymbol symbol) {
        return new ItemStack(icon(symbol));
    }

    /** The recessed housing, drawn under the band. */
    public static void housing(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 3, y - 3, x + WIDTH + 3, y + HEIGHT + 3, CasinoPanel.FRAME_EDGE);
        graphics.fill(x - 2, y - 2, x + WIDTH + 2, y + HEIGHT + 2, CasinoPanel.GOLD_DEEP);
        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, CasinoPanel.FRAME_EDGE);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF15100C);
    }

    /**
     * Draws the band at {@code position} symbols of scroll.
     *
     * <p>Four cells are drawn for three visible slots: the extra one is the symbol coming into the
     * window from above, and without it the top of the reel would be empty for part of every cell.
     */
    public static void band(GuiGraphics graphics, int x, int y, float position) {
        int base = (int) Math.floor(position);
        float frac = position - base;

        graphics.enableScissor(x, y, x + WIDTH, y + HEIGHT);
        for (int k = -1; k <= VISIBLE; k++) {
            SlotSymbol symbol = ClientSlotState.symbolAt(base + k);
            int cellY = y + CELL + Math.round((k - frac) * CELL);
            graphics.renderItem(stack(symbol), x + (WIDTH - 16) / 2, cellY + (CELL - 16) / 2);
        }
        graphics.disableScissor();

        // Shade the cells above and below the payline so the eye goes to the middle one.
        graphics.fill(x, y, x + WIDTH, y + CELL, 0x66100C08);
        graphics.fill(x, y + 2 * CELL, x + WIDTH, y + HEIGHT, 0x66100C08);
    }

    /** The payline: a gold bracket either side of the middle cell, brightened when it has stopped. */
    public static void payline(GuiGraphics graphics, int x, int y, boolean settled) {
        int colour = settled ? CasinoPanel.GOLD : CasinoPanel.GOLD_DEEP;
        graphics.fill(x - 3, y + CELL - 1, x, y + 2 * CELL + 1, colour);
        graphics.fill(x + WIDTH, y + CELL - 1, x + WIDTH + 3, y + 2 * CELL + 1, colour);
    }
}
