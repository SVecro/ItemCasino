package com.itemcasino.client.render;

import com.itemcasino.core.game.wheel.WheelMath;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the Upgrader wheel and the duel's coin with nothing but the 2D matrix stack and
 * axis-aligned fills.
 *
 * <p>Since 1.21.6 {@code GuiGraphics#pose()} is a JOML {@code Matrix3x2fStack}, so
 * {@code rotateAbout} composes a real rotation into whatever the fill emits — a rotated thin
 * rectangle is a wedge, and a fan of wedges is a disc. Approximating it this way keeps the whole
 * widget inside the sanctioned GUI API: no {@code RenderSystem} calls (which no-op or corrupt the
 * submission order since 1.21.9) and no custom {@code GuiElementRenderState} to maintain.
 */
public final class WheelRenderer {

    private static final int SEGMENTS = 180;

    private static final int RIM_DARK = 0xFF120C08;
    private static final int RIM_GOLD = 0xFFC9A227;
    private static final int RIM_GOLD_HI = 0xFFF0DA93;

    private WheelRenderer() {}

    /**
     * @param angle  current rotation of the wheel, radians
     * @param winPpm size of the winning sector in parts per million
     */
    public static void draw(GuiGraphics graphics, int cx, int cy, int radius, float angle,
                            int winPpm, int winColour, int loseColour, int hubColour) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(2F, 3F);
        disc(graphics, cx, cy, radius + 4, 0x50000000);
        graphics.pose().popMatrix();

        disc(graphics, cx, cy, radius + 4, RIM_DARK);
        disc(graphics, cx, cy, radius + 3, RIM_GOLD);
        disc(graphics, cx, cy, radius + 1, RIM_DARK);

        float winArc = WheelMath.winArc(winPpm);
        float step = WheelMath.TAU / SEGMENTS;
        int width = wedgeWidth(radius);
        // Not "angle": see WheelMath#drawRotation. The pointer must end up over the sector the
        // server decided on, and the sector under the pointer is the one at minus the rotation.
        float spin = WheelMath.drawRotation(angle);

        for (int i = 0; i < SEGMENTS; i++) {
            float from = i * step;
            boolean winning = from < winArc;
            // A wedge that straddles the boundary is drawn as losing, so the visible gold sector is
            // never larger than the real one. Erring the other way would be a lie in the player's
            // favour on screen and a loss in the ledger.
            if (winning && from + step > winArc) winning = false;

            graphics.pose().pushMatrix();
            graphics.pose().rotateAbout(spin + from, cx, cy);
            graphics.fill(cx, cy - radius, cx + width, cy, winning ? winColour : loseColour);
            // A darker band at the rim and a lighter one inside: enough of a gradient that the
            // wheel reads as a dish rather than a flat pie.
            graphics.fill(cx, cy - radius, cx + width, cy - radius + 3,
                    CasinoPanel.blend(winning ? winColour : loseColour, 0xFF000000, 0.28F));
            graphics.fill(cx, cy - radius + 7, cx + width, cy - radius + 10,
                    CasinoPanel.blend(winning ? winColour : loseColour, 0xFFFFFFFF, 0.12F));
            graphics.pose().popMatrix();
        }

        // Spokes every eighth, so the rotation is visible even on a single-colour wheel.
        for (int i = 0; i < 8; i++) {
            graphics.pose().pushMatrix();
            graphics.pose().rotateAbout(spin + i * WheelMath.TAU / 8F, cx, cy);
            graphics.fill(cx, cy - radius, cx + 1, cy, 0x2A000000);
            graphics.pose().popMatrix();
        }

        disc(graphics, cx, cy, 7, RIM_DARK);
        disc(graphics, cx, cy, 6, RIM_GOLD);
        disc(graphics, cx, cy, 4, hubColour);
        graphics.fill(cx - 1, cy - 3, cx + 1, cy + 3, RIM_GOLD_HI);
        graphics.fill(cx - 3, cy - 1, cx + 3, cy + 1, RIM_GOLD_HI);

        drawPointer(graphics, cx, cy - radius - 6);
    }

    /** A filled circle, as a fan of wedges. */
    private static void disc(GuiGraphics graphics, int cx, int cy, int radius, int colour) {
        int segments = Math.max(48, Math.min(SEGMENTS, radius * 6));
        int width = Math.max(2, (int) Math.ceil(radius * WheelMath.TAU / segments) + 1);
        for (int i = 0; i < segments; i++) {
            graphics.pose().pushMatrix();
            graphics.pose().rotateAbout(i * WheelMath.TAU / segments, cx, cy);
            graphics.fill(cx, cy - radius, cx + width, cy, colour);
            graphics.pose().popMatrix();
        }
    }

    /** Wedge chord width: generous enough to leave no seams at this radius. */
    private static int wedgeWidth(int radius) {
        return Math.max(2, (int) Math.ceil(radius * WheelMath.TAU / SEGMENTS) + 1);
    }

    /** The pointer is fixed: it is the wheel that turns. */
    private static void drawPointer(GuiGraphics graphics, int cx, int topY) {
        graphics.fill(cx - 6, topY - 3, cx + 6, topY + 2, RIM_DARK);
        graphics.fill(cx - 5, topY - 2, cx + 5, topY + 1, RIM_GOLD);
        for (int i = 0; i < 7; i++) {
            graphics.fill(cx - 5 + i, topY + 1 + i, cx + 6 - i, topY + 2 + i, RIM_DARK);
            if (i < 6) {
                graphics.fill(cx - 4 + i, topY + 1 + i, cx + 5 - i, topY + 2 + i,
                        i < 3 ? RIM_GOLD_HI : RIM_GOLD);
            }
        }
    }

    /**
     * A two-faced coin, turning.
     *
     * <p>Each side keeps its own colour for the whole spin: the player called red or blue, and a
     * coin that goes grey the moment it leaves the table tells them nothing about what it is doing.
     * Which face you are looking at is the sign of the cosine, so the colour flickers between the
     * two exactly as fast as the coin turns, and the face it lands on is the one the server already
     * decided.
     */
    public static void drawCoin(GuiGraphics graphics, int cx, int cy, int radius, float angle,
                                int frontColour, int backColour, int edgeColour,
                                boolean settled, boolean won) {
        float cos = (float) Math.cos(angle);
        int halfWidth = Math.max(1, Math.round(radius * Math.abs(cos)));
        int faceColour = cos >= 0F ? frontColour : backColour;

        graphics.pose().pushMatrix();
        graphics.pose().translate(2F, 3F);
        ellipse(graphics, cx, cy, halfWidth + 2, radius + 2, 0x50000000);
        graphics.pose().popMatrix();

        ellipse(graphics, cx, cy, halfWidth + 2, radius + 2, edgeColour);
        ellipse(graphics, cx, cy, halfWidth + 1, radius + 1, RIM_GOLD);
        ellipse(graphics, cx, cy, halfWidth, radius, faceColour);

        if (halfWidth > radius / 3) {
            ellipse(graphics, cx, cy, halfWidth - 3, radius - 3,
                    CasinoPanel.blend(faceColour, 0xFF000000, 0.22F));
            ellipse(graphics, cx, cy, halfWidth - 5, radius - 5,
                    CasinoPanel.blend(faceColour, 0xFFFFFFFF, 0.10F));
        }
        if (settled && halfWidth > radius / 2) mark(graphics, cx, cy, won);
    }

    /** A filled ellipse, row by row. */
    private static void ellipse(GuiGraphics graphics, int cx, int cy, int halfW, int halfH,
                                int colour) {
        if (halfW <= 0 || halfH <= 0) return;
        for (int dy = -halfH; dy <= halfH; dy++) {
            float t = (float) dy / halfH;
            int w = Math.round(halfW * (float) Math.sqrt(Math.max(0F, 1F - t * t)));
            if (w <= 0) continue;
            graphics.fill(cx - w, cy + dy, cx + w, cy + dy + 1, colour);
        }
    }

    /** A doubling cross on a win, a slash on a loss. Drawn thick enough to read at 1x. */
    private static void mark(GuiGraphics graphics, int cx, int cy, boolean won) {
        int colour = won ? 0xFF2C2010 : 0xFF2A1414;
        if (won) {
            graphics.fill(cx - 8, cy - 2, cx + 8, cy + 2, colour);
            graphics.fill(cx - 2, cy - 8, cx + 2, cy + 8, colour);
        } else {
            for (int i = -8; i <= 8; i++) {
                graphics.fill(cx + i - 1, cy + i - 1, cx + i + 2, cy + i + 2, colour);
            }
        }
    }
}
