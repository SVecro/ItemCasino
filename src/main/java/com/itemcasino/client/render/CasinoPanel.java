package com.itemcasino.client.render;

import com.itemcasino.menu.CasinoLayout;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Paints the casino chrome: frame, felt, and slot wells.
 *
 * <p>Drawn rather than blitted, on purpose. A background image has to agree with the slot
 * coordinates the menu hands the server, and when the two disagree the player sees a well painted
 * where no slot is — which is precisely what went wrong before: two decorative holes sat in the
 * felt while the real wager slot was somewhere else entirely. Here every well is painted from the
 * slot list itself, so the two cannot drift apart.
 */
public final class CasinoPanel {

    // --- palette ------------------------------------------------------------
    public static final int FRAME_EDGE   = 0xFF140E0A;
    public static final int FRAME_LIGHT  = 0xFF6E5038;
    public static final int FRAME_MID    = 0xFF412E1F;
    public static final int FRAME_DEEP   = 0xFF271A11;
    public static final int GOLD         = 0xFFD8B34A;
    public static final int GOLD_DEEP    = 0xFF8A6A1E;
    public static final int FELT_CENTRE  = 0xFF2E815A;
    public static final int FELT_EDGE    = 0xFF0E3A26;
    public static final int WELL_DEEP    = 0xFF14100C;
    public static final int WELL_LIGHT   = 0xFF6E5038;

    public static final int TEXT_CREAM   = 0xFFF2E7CE;
    public static final int TEXT_MUTED   = 0xFFB0A183;
    public static final int TEXT_GOLD    = 0xFFE8C860;
    public static final int TEXT_WIN     = 0xFF7BD88F;
    public static final int TEXT_LOSE    = 0xFFE07A6B;
    public static final int TEXT_PUSH    = 0xFFE3CE7A;

    private CasinoPanel() {}

    /** The whole window: shadow, bevelled frame, felt, and the player inventory wells. */
    public static void background(GuiGraphics g, int left, int top) {
        int w = CasinoLayout.WIDTH;
        int h = CasinoLayout.HEIGHT;

        g.fill(left + 4, top + 4, left + w + 4, top + h + 4, 0x55000000);

        rounded(g, left, top, w, h, FRAME_EDGE);
        rounded(g, left + 1, top + 1, w - 2, h - 2, FRAME_LIGHT);
        rounded(g, left + 2, top + 2, w - 4, h - 4, FRAME_MID);
        g.fill(left + 2, top + h - 3, left + w - 2, top + h - 2, FRAME_DEEP);

        felt(g, left + CasinoLayout.FELT_X, top + CasinoLayout.FELT_Y,
                CasinoLayout.FELT_W, CasinoLayout.FELT_H);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                well(g, left + CasinoLayout.INV_X + col * 18,
                        top + CasinoLayout.INV_Y + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            well(g, left + CasinoLayout.INV_X + col * 18, top + CasinoLayout.HOTBAR_Y);
        }
    }

    /** Green baize with a gold pinstripe and a vignette, so it reads as depth rather than a flat fill. */
    public static void felt(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 3, y - 3, x + w + 3, y + h + 3, FRAME_DEEP);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, GOLD_DEEP);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, FRAME_EDGE);

        g.fill(x, y, x + w, y + h, FELT_CENTRE);
        for (int i = 0; i < 10; i++) {
            int shade = blend(FELT_CENTRE, FELT_EDGE, (10 - i) / 10F);
            frame(g, x + i, y + i, w - 2 * i, h - 2 * i, shade);
        }
    }

    /** One 18x18 slot well, bevelled the way vanilla's are so items read as sitting inside it. */
    public static void well(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, FRAME_DEEP);
        g.fill(x - 1, y - 1, x + 17, y, WELL_DEEP);
        g.fill(x - 1, y - 1, x, y + 17, WELL_DEEP);
        g.fill(x + 16, y - 1, x + 17, y + 17, WELL_LIGHT);
        g.fill(x - 1, y + 16, x + 17, y + 17, WELL_LIGHT);
        g.fill(x, y, x + 16, y + 16, 0xFF241A12);
    }

    /** A well that wants to be noticed: the wager slot, ringed in gold. */
    public static void goldWell(GuiGraphics g, int x, int y) {
        g.fill(x - 2, y - 2, x + 18, y + 18, GOLD_DEEP);
        g.fill(x - 1, y - 1, x + 17, y + 17, FRAME_EDGE);
        g.fill(x, y, x + 16, y + 16, 0xFF1D2A22);
    }

    /** A recessed plate for text read-outs: the side gauges and the status line sit on these. */
    public static void plate(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x4A0A1610);
        frame(g, x, y, w, h, 0x66D8B34A);
    }

    // --- primitives ---------------------------------------------------------

    /** A rectangle with its four corner pixels dropped, which reads as a rounded corner at 1x. */
    public static void rounded(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x + 2, y, x + w - 2, y + h, colour);
        g.fill(x, y + 2, x + 2, y + h - 2, colour);
        g.fill(x + w - 2, y + 2, x + w, y + h - 2, colour);
        g.fill(x + 1, y + 1, x + 2, y + 2, colour);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, colour);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, colour);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, colour);
    }

    /** A one-pixel outline. */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y + 1, x + 1, y + h - 1, colour);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, colour);
    }

    /** Linear blend in ARGB; {@code t} of 1 returns {@code b}. */
    public static int blend(int a, int b, float t) {
        t = Math.max(0F, Math.min(1F, t));
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (int) (ca + (cb - ca) * t) << shift;
        }
        return out;
    }
}
