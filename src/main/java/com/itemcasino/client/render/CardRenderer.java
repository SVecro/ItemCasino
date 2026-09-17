package com.itemcasino.client.render;

import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.blackjack.Card;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Blits playing cards out of a single 13x5 atlas: thirteen ranks across, one row per suit, with the
 * card back in the fifth row. One texture instead of fifty-three keeps the sprite table small and
 * the draw calls batched.
 *
 * <p>The two animations live here because both are pure presentation over a card whose identity was
 * decided on the server long before it reached this class: a card slides in from the shoe when it
 * is dealt, and the hole card turns over on the spot when the dealer shows it. Neither can change
 * what the card is.
 */
public final class CardRenderer {

    public static final Identifier ATLAS = ItemCasino.id("textures/gui/cards.png");

    public static final int CARD_WIDTH = 24;
    public static final int CARD_HEIGHT = 34;
    private static final int ATLAS_WIDTH = 512;
    private static final int ATLAS_HEIGHT = 256;
    private static final int BACK_ROW = 4;

    private CardRenderer() {}

    public static void drawCard(GuiGraphics graphics, Card card, int x, int y) {
        shadow(graphics, x, y);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y,
                card.rank() * CARD_WIDTH, card.suit() * CARD_HEIGHT,
                CARD_WIDTH, CARD_HEIGHT, ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    public static void drawBack(GuiGraphics graphics, int x, int y) {
        shadow(graphics, x, y);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y,
                0, BACK_ROW * CARD_HEIGHT,
                CARD_WIDTH, CARD_HEIGHT, ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    private static void shadow(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 1, y + 2, x + CARD_WIDTH + 1, y + CARD_HEIGHT + 2, 0x4A000000);
    }

    /**
     * Deals a card: it travels from the shoe to its place, easing out, and lands flat.
     *
     * @param age ticks since the card was laid down; anything past {@code slideTicks} draws at rest
     */
    public static void drawDealt(GuiGraphics graphics, Card card, int x, int y,
                                 int shoeX, int shoeY, float age, int slideTicks) {
        if (age >= slideTicks || slideTicks <= 0) {
            drawCard(graphics, card, x, y);
            return;
        }
        float t = easeOutCubic(Math.max(0F, age) / slideTicks);
        graphics.pose().pushMatrix();
        graphics.pose().translate((shoeX - x) * (1F - t), (shoeY - y) * (1F - t));
        drawCard(graphics, card, x, y);
        graphics.pose().popMatrix();
    }

    /** The same travel, for a card that arrives face down. */
    public static void drawDealtBack(GuiGraphics graphics, int x, int y,
                                     int shoeX, int shoeY, float age, int slideTicks) {
        if (age >= slideTicks || slideTicks <= 0) {
            drawBack(graphics, x, y);
            return;
        }
        float t = easeOutCubic(Math.max(0F, age) / slideTicks);
        graphics.pose().pushMatrix();
        graphics.pose().translate((shoeX - x) * (1F - t), (shoeY - y) * (1F - t));
        drawBack(graphics, x, y);
        graphics.pose().popMatrix();
    }

    /**
     * Turns the hole card over: the card narrows to nothing showing its back, then widens again
     * showing its face. Squashing through the matrix stack keeps it one blit either side.
     */
    public static void drawFlip(GuiGraphics graphics, Card card, int x, int y,
                                float age, int flipTicks) {
        if (age < 0F) {
            drawBack(graphics, x, y);
            return;
        }
        if (age >= flipTicks || flipTicks <= 0) {
            drawCard(graphics, card, x, y);
            return;
        }
        float t = age / flipTicks;
        float squash = Math.abs((float) Math.cos(Math.PI * t));
        float centreX = x + CARD_WIDTH / 2F;

        graphics.pose().pushMatrix();
        graphics.pose().translate(centreX, 0F);
        graphics.pose().scale(Math.max(0.02F, squash), 1F);
        graphics.pose().translate(-centreX, 0F);
        if (t < 0.5F) drawBack(graphics, x, y);
        else drawCard(graphics, card, x, y);
        graphics.pose().popMatrix();
    }

    private static float easeOutCubic(float t) {
        float inv = 1F - Math.max(0F, Math.min(1F, t));
        return 1F - inv * inv * inv;
    }

    /** Total width a hand occupies, for centring. */
    public static int handWidth(int cards, int spacing) {
        return cards <= 0 ? 0 : (cards - 1) * spacing + CARD_WIDTH;
    }

    /** Overlap tightens as a hand grows, so a six-card hand still fits the felt. */
    public static int spacingFor(int cards, int available) {
        if (cards <= 1) return CARD_WIDTH;
        int ideal = CARD_WIDTH - 7;
        int fitted = (available - CARD_WIDTH) / (cards - 1);
        return Math.max(7, Math.min(ideal, fitted));
    }
}
