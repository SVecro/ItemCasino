package com.itemcasino.client.screen;

import com.itemcasino.client.ClientStatsState;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.network.c2s.C2SClaimMailbox;
import com.itemcasino.network.c2s.C2SRequestStats;
import com.itemcasino.network.s2c.S2CStats;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.Nullable;

/**
 * The player's own casino record, opened from the small button on the inventory screen.
 *
 * <p>Everything shown was counted by the server as each wager settled; the screen asks for it on
 * open and draws what comes back. Values are in the same points {@code /casino value} uses.
 */
public class StatsScreen extends Screen {

    private static final int W = 196;
    // Tall enough for seven games and the pot line above the pending notice; no taller than a table screen.
    private static final int H = 226;
    private static final String[] GAME_KEYS = {
            "block.itemcasino.upgrader", "block.itemcasino.predict_the_dice",
            "block.itemcasino.blackjack_table", "block.itemcasino.coin_flip",
            "block.itemcasino.slot_machine", "block.itemcasino.vault",
            "block.itemcasino.mine_field" };

    @Nullable private final Screen parent;
    private CasinoButton claim;
    private int left;
    private int top;

    public StatsScreen(@Nullable Screen parent) {
        super(Component.translatable("itemcasino.stats.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        ClientPacketDistributor.sendToServer(C2SRequestStats.INSTANCE);

        claim = addRenderableWidget(CasinoButton.of(left + 10, top + H - 26, 86, 16,
                Component.translatable("itemcasino.stats.claim"),
                b -> ClientPacketDistributor.sendToServer(C2SClaimMailbox.INSTANCE)));
        addRenderableWidget(CasinoButton.of(left + W - 96, top + H - 26, 86, 16,
                Component.translatable("gui.back"), b -> onClose()));
    }

    @Override
    public void tick() {
        super.tick();
        S2CStats stats = ClientStatsState.latest();
        claim.active = stats != null && stats.pendingItems() > 0;
        claim.visible = claim.active;
    }

    /** Drawn like the inventory it came from: the world dimmed behind it, not blurred away. */
    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left + 4, top + 4, left + W + 4, top + H + 4, 0x55000000);
        CasinoPanel.rounded(g, left, top, W, H, CasinoPanel.FRAME_EDGE);
        CasinoPanel.rounded(g, left + 1, top + 1, W - 2, H - 2, CasinoPanel.FRAME_LIGHT);
        CasinoPanel.rounded(g, left + 2, top + 2, W - 4, H - 4, CasinoPanel.FRAME_MID);
        CasinoPanel.felt(g, left + 7, top + 20, W - 14, H - 54);
        drawText(g);
    }

    private void drawText(GuiGraphics g) {
        g.drawString(font, title, left + 9, top + 7, CasinoPanel.TEXT_GOLD, false);
        S2CStats s = ClientStatsState.latest();
        if (s == null) {
            centred(g, Component.translatable("itemcasino.stats.loading"), top + 90, CasinoPanel.TEXT_MUTED);
            return;
        }
        int x = left + 14;
        int valueRight = left + W - 14;
        int y = top + 26;
        long net = s.returned() - s.staked();

        y = row(g, x, valueRight, y, "itemcasino.stats.wagers", Long.toString(s.wagers()), CasinoPanel.TEXT_CREAM);
        y = row(g, x, valueRight, y, "itemcasino.stats.record",
                s.wins() + " / " + s.losses() + " / " + s.pushes(), CasinoPanel.TEXT_CREAM);
        y = row(g, x, valueRight, y, "itemcasino.stats.staked", Fixed.format(s.staked()), CasinoPanel.TEXT_CREAM);
        y = row(g, x, valueRight, y, "itemcasino.stats.returned", Fixed.format(s.returned()), CasinoPanel.TEXT_CREAM);
        y = row(g, x, valueRight, y, "itemcasino.stats.net",
                (net >= 0 ? "+" : "-") + Fixed.format(Math.abs(net)),
                net > 0 ? CasinoPanel.TEXT_WIN : net < 0 ? CasinoPanel.TEXT_LOSE : CasinoPanel.TEXT_PUSH);
        y = row(g, x, valueRight, y, "itemcasino.stats.biggest", "+" + Fixed.format(s.biggestWin()), CasinoPanel.TEXT_GOLD);
        y = row(g, x, valueRight, y, "itemcasino.stats.jackpots",
                s.jackpots() + (s.jackpots() > 0 ? "  (" + Fixed.format(s.jackpotValue()) + ")" : ""),
                CasinoPanel.TEXT_GOLD);
        y += 3;
        g.fill(x, y, valueRight, y + 1, 0x66D8B34A);
        y += 5;
        long[] perGame = s.perGame();
        for (int i = 0; i < GAME_KEYS.length && i < perGame.length; i++) {
            y = row(g, x, valueRight, y, GAME_KEYS[i], Long.toString(perGame[i]), CasinoPanel.TEXT_MUTED);
        }
        y += 3;
        y = row(g, x, valueRight, y, "itemcasino.stats.pot", Fixed.format(s.potValue()), CasinoPanel.TEXT_GOLD);

        if (s.pendingItems() > 0) {
            Component waiting = Component.translatable("itemcasino.stats.pending", s.pendingItems());
            g.drawString(font, waiting, left + 10, top + H - 36, CasinoPanel.TEXT_PUSH, false);
        }
    }

    private int row(GuiGraphics g, int x, int right, int y, String key, String value, int colour) {
        g.drawString(font, Component.translatable(key), x, y, CasinoPanel.TEXT_MUTED, false);
        g.drawString(font, value, right - font.width(value), y, colour, false);
        return y + 10;
    }

    private void centred(GuiGraphics g, Component text, int y, int colour) {
        g.drawString(font, text, left + (W - font.width(text)) / 2, y, colour, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
