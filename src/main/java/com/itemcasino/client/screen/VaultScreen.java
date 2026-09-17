package com.itemcasino.client.screen;

import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.InfoBadge;
import com.itemcasino.client.render.ValueStepper;
import com.itemcasino.client.render.WheelRenderer;
import com.itemcasino.core.game.jackpot.JackpotOdds;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.VaultMenu;
import com.itemcasino.network.c2s.C2SSetOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.Nullable;

/**
 * The Vault.
 *
 * <p>Everything here is a number the player deserves before they decide: what is in the pot, what
 * their offering is worth, the share they are playing for, what that share would actually hand
 * over, and the odds that buys — spelled out as "one in N" rather than as a percentage, because
 * 0.08% reads as small and "one in twelve hundred" reads as what it is.
 */
public class VaultScreen extends AbstractCasinoScreen<VaultMenu> {

    private static final int DIAL_CX = CasinoLayout.CENTRE_X;
    private static final int DIAL_CY = 48;
    private static final int DIAL_R = 24;

    private static final int TOP_GAUGE_Y = 22;
    private static final int LOW_GAUGE_Y = 50;

    /**
     * The share row under the dial: [-] a slider whose knob carries the value [+] (i).
     *
     * <p>A hundred values behind two buttons was a hundred clicks. Now: drag or click the track
     * (steps of 5, Shift for 1), scroll over the row (1, Shift for 10), or hold a button (it repeats,
     * then moves by 5).
     */
    private static final int SHARE_Y = 78;
    private static final int NUDGE = 12;
    private static final int MINUS_X = CasinoLayout.LEFT_GAUGE_X;
    private static final int TRACK_X = MINUS_X + NUDGE + 3;
    private static final int PLUS_X = CasinoLayout.RIGHT_GAUGE_X - NUDGE - InfoBadge.SIZE - 3;
    private static final int TRACK_W = PLUS_X - 3 - TRACK_X;
    private static final int KNOB_W = 28;
    private static final int BADGE_X = PLUS_X + NUDGE + 3;

    @Nullable private Button lessButton;
    @Nullable private Button moreButton;
    private boolean draggingKnob;

    private final ValueStepper share;

    public VaultScreen(VaultMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.share = new ValueStepper(1, 100, menu::sharePercent,
                wanted -> ClientPacketDistributor.sendToServer(
                        new C2SSetOption(menu.containerId, wanted)));
    }

    /**
     * A floor under the drawn arc.
     *
     * <p>The real odds are often a few hundred parts per million, which at this radius is less than
     * one pixel and would draw as a wheel with no gold on it at all. The arc is widened to stay
     * visible — the number beside it is the truth, and the payload handler lands the pointer using
     * this same widened figure so the two never disagree.
     */
    public static int displayPpm(int ppm) {
        return Math.max(ppm, 1500);
    }

    @Override
    protected void init() {
        super.init();
        lessButton = addRenderableWidget(CasinoButton.of(leftPos + MINUS_X, topPos + SHARE_Y,
                NUDGE, NUDGE, Component.literal("-"), b -> pressShare(-1, b)));
        moreButton = addRenderableWidget(CasinoButton.of(leftPos + PLUS_X, topPos + SHARE_Y,
                NUDGE, NUDGE, Component.literal("+"), b -> pressShare(1, b)));
        refreshWidgets();
    }

    @Override
    protected String wagerLabelKey() { return "itemcasino.button.draw"; }

    @Override
    protected boolean canWager() {
        return menu.canAct() && menu.drawPpm() > 0;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        boolean settable = canSetShare();
        if (lessButton != null) lessButton.active = settable && share() > 1;
        if (moreButton != null) moreButton.active = settable && share() < 100;
    }

    @Override
    protected void containerTick() {
        share.tick();
        super.containerTick();
    }

    private boolean canSetShare() {
        return menu.canAct() && menu.gameState().acceptsItems();
    }

    private int share() {
        return share.value();
    }

    /** One percent now (ten with Shift); held, it repeats and then moves by five (or ten). */
    private void pressShare(int direction, Button button) {
        if (!canSetShare()) return;
        boolean shift = Minecraft.getInstance().hasShiftDown();
        share.press(direction, shift ? 10 : 1, shift ? 10 : 5,
                () -> button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed());
    }

    private boolean overTrack(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        return x >= TRACK_X && x < TRACK_X + TRACK_W && y >= SHARE_Y - 1 && y < SHARE_Y + NUDGE + 1;
    }

    private boolean overShareRow(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        return x >= MINUS_X && x < BADGE_X && y >= SHARE_Y - 2 && y < SHARE_Y + NUDGE + 2;
    }

    /** Where the knob's centre sits for a value, panel-relative. */
    private static int knobCentre(int value) {
        int travel = TRACK_W - KNOB_W;
        return TRACK_X + KNOB_W / 2 + Math.round(travel * (value - 1) / 99F);
    }

    /** The value under a mouse position: steps of five while dragging, one with Shift. */
    private void dragTo(double mouseX) {
        int travel = TRACK_W - KNOB_W;
        double t = (mouseX - leftPos - TRACK_X - KNOB_W / 2.0) / travel;
        t = Math.max(0, Math.min(1, t));
        double raw = 1 + t * 99;
        int wanted = Minecraft.getInstance().hasShiftDown()
                ? (int) Math.round(raw)
                : Math.max(1, (int) Math.round(raw / 5.0) * 5);
        share.set(wanted);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && canSetShare() && overTrack(event.x(), event.y())) {
            draggingKnob = true;
            dragTo(event.x());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingKnob) {
            if (canSetShare()) dragTo(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        share.release();
        if (draggingKnob) {
            draggingKnob = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && canSetShare() && overShareRow(mouseX, mouseY)) {
            share.nudge(scrollY > 0 ? 1 : -1, Minecraft.getInstance().hasShiftDown() ? 10 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        boolean settled = ClientSessionState.phase() == ClientSessionState.Phase.RESULT;
        boolean won = ClientSessionState.won();

        // The dial is the odds made visible: the gold arc is the sliver the draw actually buys.
        WheelRenderer.draw(graphics, leftPos + DIAL_CX, topPos + DIAL_CY, DIAL_R,
                ClientSessionState.currentAngle(partialTick),
                displayPpm(ClientSessionState.quotedPpm() > 0
                        ? ClientSessionState.quotedPpm() : menu.drawPpm()),
                0xFFF2C14E,
                settled ? (won ? 0xFF2E6B4A : 0xFF3A2F3E) : 0xFF3A2F3E, 0xFF1B1B22);
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        int rightX = CasinoLayout.RIGHT_GAUGE_X - CasinoLayout.GAUGE_W;

        gauge(graphics, CasinoLayout.LEFT_GAUGE_X, TOP_GAUGE_Y, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.the_pot"),
                worth(menu.potMilli()), CasinoPanel.TEXT_GOLD);
        gauge(graphics, CasinoLayout.LEFT_GAUGE_X, LOW_GAUGE_Y, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.offering"),
                worth(menu.offeringMilli()), CasinoPanel.TEXT_CREAM);

        int ppm = menu.drawPpm();
        gauge(graphics, rightX, TOP_GAUGE_Y, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.your_odds"),
                oneIn(ppm), ppm <= 0 ? CasinoPanel.TEXT_MUTED
                        : atCeiling(ppm) ? CasinoPanel.TEXT_PUSH : CasinoPanel.TEXT_WIN);
        gauge(graphics, rightX, LOW_GAUGE_Y, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.if_you_win"),
                worth(menu.prizeMilli()), CasinoPanel.TEXT_GOLD);

        renderShareSlider(graphics);

        centredFit(graphics, status(), DIAL_CX, 96, CasinoLayout.FELT_W - 8, statusColour());
    }

    /** The track, filled in gold up to the knob, with a tick at each quarter; the knob shows the share. */
    private void renderShareSlider(GuiGraphics graphics) {
        boolean live = canSetShare();
        int value = share();
        int centre = knobCentre(value);
        int lineY = SHARE_Y + NUDGE / 2 - 1;
        graphics.fill(TRACK_X, lineY - 1, TRACK_X + TRACK_W, lineY + 2, CasinoPanel.FRAME_EDGE);
        graphics.fill(TRACK_X + 1, lineY, centre, lineY + 1,
                live ? CasinoPanel.GOLD : CasinoPanel.GOLD_DEEP);
        for (int quarter = 25; quarter <= 75; quarter += 25) {
            int x = knobCentre(quarter);
            graphics.fill(x, lineY - 2, x + 1, lineY + 3, CasinoPanel.FRAME_LIGHT);
        }
        int left = centre - KNOB_W / 2;
        CasinoPanel.rounded(graphics, left, SHARE_Y, KNOB_W, NUDGE,
                live ? (draggingKnob ? CasinoPanel.TEXT_GOLD : CasinoPanel.GOLD) : CasinoPanel.GOLD_DEEP);
        CasinoPanel.rounded(graphics, left + 1, SHARE_Y + 1, KNOB_W - 2, NUDGE - 2,
                live ? 0xFF3A2A12 : 0xFF2A2018);
        Component label = Component.literal(value + "%");
        centred(graphics, label, centre + 1, SHARE_Y + 2,
                live ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_MUTED);
    }

    @Override
    protected Component infoText() {
        int returnPpm = menu.returnPpm();
        int maxPpm = menu.maxPpm();
        return Component.translatable("itemcasino.info.vault",
                returnPpm > 0 ? InfoBadge.percent(returnPpm) : "--",
                maxPpm > 0 ? InfoBadge.percent(maxPpm) : "--",
                InfoBadge.percent(menu.potSharePpm()));
    }

    /**
     * Odds as "1 in N". A draw is measured in thousandths of a percent and nobody has an intuition
     * for that; everybody has one for a number they can picture themselves failing.
     */
    private Component oneIn(int ppm) {
        if (ppm <= 0) return Component.literal("--");
        if (ppm >= 100_000) {
            // Past one in ten a percentage is the clearer number again.
            return Component.literal(InfoBadge.percent(ppm) + " %");
        }
        long n = Math.max(1, JackpotOdds.PPM / ppm);
        return Component.translatable("itemcasino.label.one_in", n);
    }

    private Component worth(int milli) {
        return milli < 0 ? Component.literal("--") : Component.literal(Fixed.format(milli * 1000L));
    }

    /** At the ceiling the offering gauge stops rising with the stack, which needs explaining. */
    private boolean atCeiling(int ppm) {
        int max = menu.maxPpm();
        return max > 0 && ppm >= max;
    }

    private Component status() {
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT) {
            if (!ClientSessionState.won()) return Component.translatable("itemcasino.label.vault_lost");
            return share() >= 100
                    ? Component.translatable("itemcasino.label.vault_won")
                    : Component.translatable("itemcasino.label.vault_won_share", share());
        }
        if (menu.potMilli() <= 0) return Component.translatable("itemcasino.label.vault_empty");
        if (!menu.ownWagerStack().isEmpty() && menu.prizeMilli() == 0) {
            return Component.translatable("itemcasino.label.vault_share_nothing");
        }
        if (menu.drawPpm() <= 0) return Component.translatable("itemcasino.label.vault_hint");
        if (atCeiling(menu.drawPpm())) {
            return Component.translatable("itemcasino.label.vault_takes_part");
        }
        return Component.translatable("itemcasino.label.vault_ready");
    }

    private int statusColour() {
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT) {
            return ClientSessionState.won() ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_LOSE;
        }
        return CasinoPanel.TEXT_MUTED;
    }
}
