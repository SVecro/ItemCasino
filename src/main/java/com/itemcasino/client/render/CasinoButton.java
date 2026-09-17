package com.itemcasino.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button that belongs on a card table.
 *
 * <p>Vanilla's button is a pale grey bar designed for a stone-grey inventory; dropped onto dark
 * baize it reads as a hole punched through the table, and four of them in a row was most of what
 * made the old blackjack screen look unfinished. This one is the same widget with a different coat:
 * a bevelled plate, gold trim when it is live, and a flat dead grey when it is not, so "you cannot
 * press this" is legible at a glance instead of only on hover.
 *
 * <p>The look goes in {@code renderContents} rather than {@code renderWidget}: since 1.21.9 the
 * latter is final on {@code AbstractButton}, which calls the former and then places the cursor.
 * Skipping {@code renderDefaultSprite} is the whole point — that is the grey bar.
 */
public class CasinoButton extends Button {

    private static final int FACE_IDLE  = 0xFF33241A;
    private static final int FACE_HOVER = 0xFF4A3524;
    private static final int FACE_OFF   = 0xFF241C17;
    private static final int EDGE_LIGHT = 0xFF7A5A3C;
    private static final int EDGE_DARK  = 0xFF150E09;
    private static final int TEXT_OFF   = 0xFF6B6058;

    public CasinoButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public static CasinoButton of(int x, int y, int width, int height, Component message,
                                  OnPress onPress) {
        return new CasinoButton(x, y, width, height, message, onPress);
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean live = this.active;
        boolean hot = live && isHoveredOrFocused();

        graphics.fill(x, y + 1, x + w, y + h + 1, 0x40000000);
        CasinoPanel.rounded(graphics, x, y, w, h, EDGE_DARK);
        CasinoPanel.rounded(graphics, x + 1, y + 1, w - 2, h - 2,
                live ? (hot ? CasinoPanel.GOLD : EDGE_LIGHT) : 0xFF2C231D);
        CasinoPanel.rounded(graphics, x + 1, y + 2, w - 2, h - 3,
                live ? (hot ? FACE_HOVER : FACE_IDLE) : FACE_OFF);

        int colour = live ? (hot ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_CREAM) : TEXT_OFF;
        Component label = getMessage();
        var font = Minecraft.getInstance().font;
        int textX = x + Math.max(2, (w - font.width(label)) / 2);
        graphics.drawString(font, label, textX, y + (h - 8) / 2 + 1, colour, false);
    }
}
