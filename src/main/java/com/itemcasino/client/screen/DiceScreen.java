package com.itemcasino.client.screen;

import com.itemcasino.client.CasinoSounds;
import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.InfoBadge;
import com.itemcasino.client.render.ValueStepper;
import com.itemcasino.core.game.Dice;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.DiceMenu;
import com.itemcasino.network.c2s.C2SSetOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.Nullable;

/**
 * Predict the Dice.
 *
 * <p>One track from 0 to 100 is the whole bet: a gold line the player drags, green where the roll
 * wins and red where it loses. The number above counts to the roll the server already made, and a
 * marker lands on the track where it came up, so the player sees in one glance which side it fell.
 */
public class DiceScreen extends AbstractCasinoScreen<DiceMenu> {

    private static final int WIN_GREEN = 0xFF2E8B57;
    private static final int LOSE_RED = 0xFF8E2F2A;

    private static final int NUMBER_Y = 21;
    private static final int NUMBER_W = 72;
    private static final int NUMBER_H = 28;

    private static final int TRACK_X = 18;
    private static final int TRACK_W = CasinoLayout.WIDTH - 2 * TRACK_X;   // 180
    private static final int TRACK_Y = 59;
    private static final int TICKS_Y = 69;

    private static final int NUDGE = 12;
    private static final int NUDGE_Y = 80;
    private static final int MINUS_X = CasinoLayout.LEFT_GAUGE_X;
    private static final int PLUS_X = CasinoLayout.RIGHT_GAUGE_X - NUDGE;

    private static final int DIRECTION_W = 64;
    private static final int ROLL_X = CasinoLayout.BUTTONS_X + DIRECTION_W + 4;
    private static final int ROLL_W = CasinoLayout.BUTTONS_RIGHT - ROLL_X;

    @Nullable private Button directionButton;
    @Nullable private Button lessButton;
    @Nullable private Button moreButton;
    private boolean dragging;

    private final ValueStepper chance;
    /** The direction just asked for, until the server's copy agrees. */
    private int pendingOver = -1;
    private int pendingOverTicks;

    /** The whole number shown last tick, so the counter's ticking can be heard. */
    private int lastShownWhole = -1;

    public DiceScreen(DiceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.chance = new ValueStepper(menu::minChance, menu::maxChance, menu::chance,
                wanted -> ClientPacketDistributor.sendToServer(
                        new C2SSetOption(menu.containerId, Dice.pack(wanted, over()))));
    }

    @Override
    protected int primaryButtonX() { return ROLL_X; }

    @Override
    protected int primaryButtonWidth() { return ROLL_W; }

    @Override
    protected String wagerLabelKey() { return "itemcasino.button.roll"; }

    @Override
    protected void init() {
        super.init();
        directionButton = addRenderableWidget(CasinoButton.of(
                leftPos + CasinoLayout.BUTTONS_X, topPos + CasinoLayout.ROW_Y,
                DIRECTION_W, CasinoLayout.BUTTON_H, directionLabel(), b -> toggleDirection()));
        lessButton = addRenderableWidget(CasinoButton.of(leftPos + MINUS_X, topPos + NUDGE_Y,
                NUDGE, NUDGE, Component.literal("-"), b -> pressChance(-1, b)));
        moreButton = addRenderableWidget(CasinoButton.of(leftPos + PLUS_X, topPos + NUDGE_Y,
                NUDGE, NUDGE, Component.literal("+"), b -> pressChance(1, b)));
        refreshWidgets();
    }

    // ------------------------------------------------------------------ the bet

    private boolean over() {
        return pendingOver >= 0 ? pendingOver == 1 : menu.over();
    }

    private int chance() { return chance.value(); }

    private long multiplierPpm() {
        return Dice.multiplierPpm(chance(), menu.edgePpm());
    }

    private boolean canSetBet() {
        return menu.canAct() && menu.gameState().acceptsItems();
    }

    private boolean hasCard() {
        return com.itemcasino.chips.ChipCards.isCard(menu.ownWagerStack());
    }

    @Override
    protected boolean canWager() {
        return menu.canAct() && hasCard() && shownBet() >= 1;
    }

    private Component directionLabel() {
        return Component.translatable(over() ? "itemcasino.label.dice_over" : "itemcasino.label.dice_under");
    }

    /** Flips the side of the line that wins, keeping the chance: the green zone mirrors. */
    private void toggleDirection() {
        if (!canSetBet()) return;
        boolean flipped = !over();
        pendingOver = flipped ? 1 : 0;
        pendingOverTicks = 30;
        ClientPacketDistributor.sendToServer(
                new C2SSetOption(menu.containerId, Dice.pack(chance(), flipped)));
    }

    /** One percent a click (a tenth with Shift); held, it repeats and then moves by five. */
    private void pressChance(int direction, Button button) {
        if (!canSetBet()) return;
        boolean shift = Minecraft.getInstance().hasShiftDown();
        chance.press(direction, shift ? 10 : 100, shift ? 10 : 500,
                () -> button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed());
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        boolean settable = canSetBet();
        if (directionButton != null) {
            directionButton.active = settable;
            directionButton.setMessage(directionLabel());
        }
        if (lessButton != null) lessButton.active = settable && chance() > menu.minChance();
        if (moreButton != null) moreButton.active = settable && chance() < menu.maxChance();
    }

    @Override
    protected void containerTick() {
        chance.tick();
        if (pendingOver >= 0 && ((menu.over() ? 1 : 0) == pendingOver || --pendingOverTicks <= 0)) {
            pendingOver = -1;
        }
        // A click each tick the counter moves on, thinning out as it slows onto the roll.
        if (rolling()) {
            int whole = shownRoll(0F) / 100;
            if (lastShownWhole >= 0 && whole != lastShownWhole) CasinoSounds.wheelTick(whole);
            lastShownWhole = whole;
        } else {
            lastShownWhole = -1;
        }
        super.containerTick();
    }

    // ------------------------------------------------------------------ the track

    private static int xOfLine(int line) {
        return TRACK_X + Math.round(TRACK_W * line / (float) Dice.ROLLS);
    }

    private boolean overTrack(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        return x >= TRACK_X - 4 && x <= TRACK_X + TRACK_W + 4 && y >= TRACK_Y - 8 && y <= TRACK_Y + 10;
    }

    /** The line follows the mouse in steps of half a percent, a tenth with Shift. */
    private void dragTo(double mouseX) {
        double t = (mouseX - leftPos - TRACK_X) / TRACK_W;
        int line = (int) Math.round(Math.max(0, Math.min(1, t)) * Dice.ROLLS);
        int step = Minecraft.getInstance().hasShiftDown() ? 10 : 50;
        int wanted = Dice.chanceAt(line, over());
        chance.set(Math.round(wanted / (float) step) * step);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && canSetBet() && overTrack(event.x(), event.y())) {
            dragging = true;
            dragTo(event.x());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            if (canSetBet()) dragTo(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        chance.release();
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double y = mouseY - topPos;
        if (scrollY != 0 && canSetBet() && y >= TRACK_Y - 10 && y <= NUDGE_Y + NUDGE) {
            chance.nudge(scrollY > 0 ? 1 : -1, Minecraft.getInstance().hasShiftDown() ? 10 : 100);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------ painting

    private boolean rolling() {
        return ClientSessionState.phase() == ClientSessionState.Phase.SPINNING
                && ClientSessionState.diceRoll() >= 0;
    }

    /** The roll on show: counting up during the animation, then the roll itself. */
    private int shownRoll(float partialTick) {
        if (rolling()) {
            int target = ClientSessionState.diceRoll();
            float p = ClientSessionState.progress(partialTick);
            // Races across the track a couple of times, then settles on the roll.
            double sweep = (1 - p) * 2.0 * Dice.ROLLS;
            double value = (target + sweep) % Dice.ROLLS;
            return (int) Math.max(0, Math.min(Dice.ROLLS - 1, value));
        }
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT && ClientSessionState.diceRoll() >= 0) {
            return ClientSessionState.diceRoll();
        }
        return menu.lastRoll();
    }

    /** Whether the roll on show won, once it has landed. */
    private boolean shownWin() {
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT && ClientSessionState.diceRoll() >= 0) {
            return ClientSessionState.won();
        }
        return menu.lastWin();
    }

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x0 = leftPos + TRACK_X;
        int y0 = topPos + TRACK_Y;
        boolean over = over();
        int line = Dice.line(chance(), over);
        int lineX = leftPos + xOfLine(line);

        // The track: green where this bet wins, red where it loses.
        graphics.fill(x0 - 1, y0 - 4, x0 + TRACK_W + 1, y0 + 4, CasinoPanel.FRAME_EDGE);
        int winFrom = over ? lineX : x0;
        int winTo = over ? x0 + TRACK_W : lineX;
        int loseFrom = over ? x0 : lineX;
        int loseTo = over ? lineX : x0 + TRACK_W;
        graphics.fill(loseFrom, y0 - 3, loseTo, y0 + 3, LOSE_RED);
        graphics.fill(winFrom, y0 - 3, winTo, y0 + 3, WIN_GREEN);
        graphics.fill(winFrom, y0 - 3, winTo, y0 - 2, 0x40FFFFFF);

        // The line the player drags.
        boolean live = canSetBet();
        int handle = dragging ? CasinoPanel.TEXT_GOLD : live ? CasinoPanel.GOLD : CasinoPanel.GOLD_DEEP;
        graphics.fill(lineX - 2, y0 - 8, lineX + 3, y0 + 8, CasinoPanel.FRAME_EDGE);
        graphics.fill(lineX - 1, y0 - 7, lineX + 2, y0 + 7, handle);

        // Where the roll came up.
        int roll = shownRoll(partialTick);
        if (roll >= 0) {
            int rx = leftPos + xOfLine(roll);
            boolean landed = !rolling();
            int colour = landed ? (shownWin() ? CasinoPanel.TEXT_WIN : CasinoPanel.TEXT_LOSE) : CasinoPanel.TEXT_CREAM;
            graphics.fill(rx, y0 - 5, rx + 1, y0 + 5, colour);
            for (int i = 0; i < 3; i++) {
                graphics.fill(rx - i, y0 - 7 - i, rx + i + 1, y0 - 6 - i, colour);
            }
        }
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        int chanceNow = chance();
        long multiplier = multiplierPpm();

        gauge(graphics, CasinoLayout.LEFT_GAUGE_X, NUMBER_Y, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.chance"),
                Component.literal(InfoBadge.percent(chanceNow * 100L) + " %"), CasinoPanel.TEXT_PUSH);
        gauge(graphics, CasinoLayout.RIGHT_GAUGE_X - CasinoLayout.GAUGE_W, NUMBER_Y, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.dice_pays"),
                Component.literal(InfoBadge.times(multiplier)), CasinoPanel.TEXT_WIN);

        renderRollNumber(graphics);

        // 0, 25, 50, 75, 100 under the track.
        for (int mark = 0; mark <= 100; mark += 25) {
            centred(graphics, Component.literal(Integer.toString(mark)), xOfLine(mark * 100), TICKS_Y,
                    CasinoPanel.TEXT_MUTED);
        }

        // Between the nudge buttons: exactly which rolls win.
        Component rule = Component.translatable(over() ? "itemcasino.label.dice_wins_over" : "itemcasino.label.dice_wins_under",
                twoDecimals(Dice.line(chanceNow, over())));
        centredFit(graphics, rule, CasinoLayout.CENTRE_X, NUDGE_Y + 2, PLUS_X - MINUS_X - NUDGE - 8,
                CasinoPanel.TEXT_CREAM);

        centredFit(graphics, status(), CasinoLayout.CENTRE_X, 96, CasinoLayout.FELT_W - 8, statusColour());
    }

    private void renderRollNumber(GuiGraphics graphics) {
        int left = CasinoLayout.CENTRE_X - NUMBER_W / 2;
        CasinoPanel.plate(graphics, left, NUMBER_Y, NUMBER_W, NUMBER_H);
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int roll = shownRoll(partial);
        String text = roll < 0 ? "--" : twoDecimals(roll);
        int colour = roll < 0 || rolling() ? CasinoPanel.TEXT_CREAM
                : shownWin() ? CasinoPanel.TEXT_WIN : CasinoPanel.TEXT_LOSE;

        graphics.pose().pushMatrix();
        graphics.pose().translate(CasinoLayout.CENTRE_X, NUMBER_Y + NUMBER_H / 2F - font.lineHeight + 1);
        graphics.pose().scale(2F, 2F);
        graphics.drawString(font, text, -font.width(text) / 2, 0, colour, false);
        graphics.pose().popMatrix();
    }

    /** 4937 as "49.37", with the player's decimal separator. */
    private static String twoDecimals(int hundredths) {
        int v = Math.max(0, hundredths);
        String text = (v / 100) + "." + String.format(java.util.Locale.ROOT, "%02d", v % 100);
        return text.replace(".", net.minecraft.client.resources.language.I18n.get("itemcasino.decimal_separator"));
    }

    @Override
    protected Component infoText() {
        int edge = menu.edgePpm();
        return Component.translatable("itemcasino.info.dice",
                InfoBadge.percent(edge), InfoBadge.percent(1_000_000L - edge),
                InfoBadge.percent(menu.minChance() * 100L), InfoBadge.percent(menu.maxChance() * 100L));
    }

    private Component status() {
        if (rolling()) return Component.translatable("itemcasino.label.dice_rolling");
        if (!hasCard() && menu.gameState().acceptsItems()) return Component.translatable("itemcasino.label.needs_card");
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT && ClientSessionState.diceRoll() >= 0) {
            return ClientSessionState.won()
                    ? Component.translatable("itemcasino.label.dice_won", InfoBadge.times(multiplierPpmOfLastBet()))
                    : Component.translatable("itemcasino.label.dice_lost");
        }
        if (!hasCard()) return Component.translatable("itemcasino.label.needs_card");
        return Component.translatable("itemcasino.label.dice_hint");
    }

    /** The multiplier of the bet that was rolled, from the chance the server froze with it. */
    private long multiplierPpmOfLastBet() {
        int frozen = menu.oddsPpm() / 100;
        return Dice.multiplierPpm(frozen > 0 ? frozen : chance(), menu.edgePpm());
    }

    private int statusColour() {
        if (rolling()) return CasinoPanel.TEXT_MUTED;
        if (!hasCard() && menu.gameState().acceptsItems()) return CasinoPanel.TEXT_PUSH;
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT && ClientSessionState.diceRoll() >= 0) {
            return ClientSessionState.won() ? CasinoPanel.TEXT_WIN : CasinoPanel.TEXT_LOSE;
        }
        return hasCard() ? CasinoPanel.TEXT_MUTED : CasinoPanel.TEXT_PUSH;
    }
}
