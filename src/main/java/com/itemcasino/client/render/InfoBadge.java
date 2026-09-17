package com.itemcasino.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The small (i) that explains a table's rules on hover.
 *
 * <p>Rules belong next to the number they explain rather than in a manual nobody opens: the dice
 * edge, the Vault's share, the mine field's multipliers are each one hover away from the gauge that
 * shows them. Every figure in the text comes from the server, so the explanation cannot drift from
 * the game.
 */
public final class InfoBadge {

    public static final int SIZE = 9;
    private static final int TOOLTIP_WIDTH = 190;

    private InfoBadge() {}

    /**
     * Draws the badge at absolute screen coordinates and, when the mouse is over it, queues the
     * wrapped explanation as this frame's tooltip.
     */
    public static void draw(GuiGraphics g, Font font, int x, int y, int mouseX, int mouseY,
                            Component explanation) {
        boolean hot = mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE;
        CasinoPanel.rounded(g, x, y, SIZE, SIZE, hot ? CasinoPanel.GOLD : CasinoPanel.GOLD_DEEP);
        CasinoPanel.rounded(g, x + 1, y + 1, SIZE - 2, SIZE - 2, 0xFF1D2A22);
        g.drawString(font, "i", x + 4 - font.width("i") / 2, y + 1,
                hot ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_CREAM, false);
        if (hot) g.setTooltipForNextFrame(font, font.split(explanation, TOOLTIP_WIDTH), mouseX, mouseY);
    }

    /**
     * A ppm figure as a short percentage in the player's language: 495 000 is "49.5" in English and
     * "49,5" in French, 10 000 is "1". The separator is a translation key so each language owns it.
     */
    public static String percent(long ppm) {
        String text = String.format(Locale.ROOT, "%.2f", ppm / 10_000.0);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        }
        return text.replace(".", I18n.get("itemcasino.decimal_separator"));
    }

    /** A multiplier in ppm as "x1.26" (two decimals, localised separator). */
    public static String times(long ppm) {
        String text = String.format(Locale.ROOT, "%.2f", ppm / 1_000_000.0);
        return "x" + text.replace(".", I18n.get("itemcasino.decimal_separator"));
    }
}
