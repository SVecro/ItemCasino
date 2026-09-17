package com.itemcasino.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The win, in big gold letters, over the table.
 *
 * <p>Shown once the table has finished telling the story — the wheel stopped, the last card down —
 * and only to the player whose win it is. It pops in, pulses, throws sparks, and gets out of the
 * way on its own after a few seconds; it never takes a click, so the next bet is never blocked by
 * a celebration of the last one.
 */
public final class WinBanner {

    /** Tiers match {@code S2CPayoutReady}: 1 a win, 2 a big win, 3 the Vault's pot. */
    private static final int[] LIFE = { 0, 70, 95, 120 };
    private static final int POP_TICKS = 7;
    private static final int FADE_TICKS = 12;

    private int age = -1;
    private int tier;
    private Component amount = Component.empty();
    private List<ItemStack> stacks = List.of();

    public void show(int tier, Component amount, List<ItemStack> stacks) {
        this.tier = Mth.clamp(tier, 1, 3);
        this.amount = amount == null ? Component.empty() : amount;
        this.stacks = stacks == null ? List.of() : List.copyOf(stacks.subList(0, Math.min(6, stacks.size())));
        this.age = 0;
    }

    public void hide() {
        age = -1;
    }

    public boolean active() {
        return age >= 0;
    }

    public void tick() {
        if (age < 0) return;
        if (++age >= LIFE[tier]) age = -1;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.9F;
        float c3 = c1 + 1F;
        float x = t - 1F;
        return 1F + c3 * x * x * x + c1 * x * x;
    }

    private static int withAlpha(int argb, float alpha) {
        int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * alpha), 5, 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Draws the banner centred on a point, above everything already on the screen. */
    public void render(GuiGraphics g, Font font, int centreX, int centreY, float partialTick) {
        if (age < 0) return;
        float t = age + partialTick;
        int life = LIFE[tier];
        float alpha = t > life - FADE_TICKS ? Math.max(0F, (life - t) / FADE_TICKS) : 1F;
        float pop = t < POP_TICKS ? Math.max(0.05F, easeOutBack(t / POP_TICKS)) : 1F;
        float pulse = 1F + 0.04F * (float) Math.sin(t * 0.45F) * (tier >= 2 ? 1.6F : 1F);

        // Above the slots and their items: those were drawn in the screen's own stratum.
        g.nextStratum();

        Component title = Component.translatable(switch (tier) {
            case 3 -> "itemcasino.banner.jackpot";
            case 2 -> "itemcasino.banner.big";
            default -> "itemcasino.banner.win";
        });
        boolean hasAmount = !amount.getString().isEmpty();
        boolean hasStacks = !stacks.isEmpty();
        float titleScale = tier == 3 ? 2.6F : tier == 2 ? 2.3F : 2F;
        int width = Math.max(120, Math.max((int) (font.width(title) * titleScale),
                (int) (font.width(amount) * 1.5F)) + 24);
        width = Math.max(width, stacks.size() * 20 + 24);
        int height = 14 + (int) (9 * titleScale) + (hasAmount ? 17 : 0) + (hasStacks ? 20 : 0);

        g.pose().pushMatrix();
        g.pose().translate(centreX, centreY);
        g.pose().scale(pop * pulse, pop * pulse);

        int left = -width / 2;
        int top = -height / 2;

        // Sparks: gold flecks thrown outwards from behind the plate, more of them for a bigger win.
        int sparks = tier == 3 ? 36 : tier == 2 ? 24 : 14;
        for (int i = 0; i < sparks; i++) {
            float angle = (float) (i * (Math.PI * 2 / sparks)) + t * 0.02F * (i % 2 == 0 ? 1 : -1);
            float travel = ((t * (1.6F + (i % 5) * 0.35F)) + i * 7F) % (width * 0.75F);
            float sx = (float) Math.cos(angle) * (width * 0.35F + travel);
            float sy = (float) Math.sin(angle) * (height * 0.45F + travel * 0.55F);
            float fade = 1F - travel / (width * 0.75F);
            int colour = (i % 3 == 0) ? 0xFFFFF4C0 : (i % 3 == 1) ? CasinoPanel.TEXT_GOLD : 0xFFFFB02E;
            int size = i % 4 == 0 ? 3 : 2;
            g.fill((int) sx, (int) sy, (int) sx + size, (int) sy + size, withAlpha(colour, alpha * fade));
        }

        // The plate: a dark glass with a gold rim that shimmers.
        float shimmer = 0.5F + 0.5F * (float) Math.sin(t * 0.6F);
        int rim = CasinoPanel.blend(CasinoPanel.GOLD_DEEP, 0xFFFFF0A0, shimmer);
        CasinoPanel.rounded(g, left - 3, top - 3, width + 6, height + 6, withAlpha(0xFF000000, alpha * 0.45F));
        CasinoPanel.rounded(g, left - 2, top - 2, width + 4, height + 4, withAlpha(rim, alpha));
        CasinoPanel.rounded(g, left, top, width, height, withAlpha(0xF0120C08, alpha));
        CasinoPanel.frame(g, left + 2, top + 2, width - 4, height - 4, withAlpha(CasinoPanel.GOLD_DEEP, alpha));
        // A light band sweeping across the plate.
        int sweep = left + (int) (((t * 5F) % (width + 40)) - 20);
        for (int dx = 0; dx < 10; dx++) {
            int x = sweep + dx;
            if (x <= left + 2 || x >= left + width - 2) continue;
            g.fill(x, top + 3, x + 1, top + height - 3, withAlpha(0x22FFFFFF, alpha * (1F - Math.abs(dx - 5) / 5F)));
        }

        // The title, big, flashing between golds on a big win.
        int flash = tier >= 2 && ((int) t / 3) % 2 == 0 ? 0xFFFFFFFF : CasinoPanel.TEXT_GOLD;
        int y = top + 7;
        drawScaled(g, font, title, 0, y, titleScale, withAlpha(flash, alpha));
        y += (int) (9 * titleScale) + 4;

        if (hasAmount) {
            drawScaled(g, font, amount, 0, y, 1.5F, withAlpha(0xFF7BF08F, alpha));
            y += 17;
        }
        if (hasStacks && alpha > 0.3F) {
            int x = -stacks.size() * 20 / 2 + 2;
            for (ItemStack stack : stacks) {
                g.renderItem(stack, x, y);
                g.renderItemDecorations(font, stack, x, y);
                x += 20;
            }
        }
        g.pose().popMatrix();
    }

    /** Centred on {@code x}, with a dark drop shadow so it reads over the sparks. */
    private static void drawScaled(GuiGraphics g, Font font, Component text, int x, int y, float scale, int argb) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        int w = font.width(text);
        int shadow = withAlpha(0xFF3A2000, ((argb >>> 24) & 0xFF) / 255F);
        g.drawString(font, text, -w / 2 + 1, 1, shadow, false);
        g.drawString(font, text, -w / 2, 0, argb, false);
        g.pose().popMatrix();
    }
}
