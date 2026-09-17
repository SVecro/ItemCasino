package com.itemcasino.client.screen;

import com.itemcasino.client.CasinoSounds;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.InfoBadge;
import com.itemcasino.client.render.ValueStepper;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.MineField;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.MineFieldMenu;
import com.itemcasino.network.c2s.C2SMineAction;
import com.itemcasino.network.c2s.C2SSetOption;
import com.itemcasino.session.MineFieldSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.Nullable;

/**
 * The mine field.
 *
 * <p>The screen knows only what the server has already decided: which tiles were turned and came up
 * safe, and — once the board is over — where the mines were. A click is a request; the tile turns
 * when the menu's data says it did, and the chime and the explosion follow that data rather than
 * the click.
 */
public class MineFieldScreen extends AbstractCasinoScreen<MineFieldMenu> {

    private static final int TILE = 13;
    private static final int PITCH = 15;
    private static final int GRID = MineField.SIDE * PITCH - (PITCH - TILE);   // 73
    private static final int GRID_X = CasinoLayout.CENTRE_X - GRID / 2;       // 72
    private static final int GRID_Y = 24;

    private static final int LEFT_X = CasinoLayout.LEFT_GAUGE_X;
    private static final int RIGHT_X = CasinoLayout.RIGHT_GAUGE_X - CasinoLayout.GAUGE_W;
    private static final int MINES_Y = 50;
    private static final int NUDGE = 12;

    @Nullable private Button fewerButton;
    @Nullable private Button moreButton;

    private final ValueStepper mines;

    /** What the last tick saw, so a change in the data can be replayed as a sound and a flash. */
    private boolean primed;
    private int seenRevealed;
    private int seenOutcome;
    private int seenToken;
    private final int[] flash = new int[MineField.TILES];
    private int boomTicks;
    /** The tile just clicked, drawn pressed until the server answers. */
    private int pressedTile = -1;
    private int pressedTicks;

    public MineFieldScreen(MineFieldMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.mines = new ValueStepper(MineField.MIN_MINES, MineField.MAX_MINES, menu::mines,
                wanted -> ClientPacketDistributor.sendToServer(
                        new C2SSetOption(menu.containerId, wanted)));
    }

    @Override
    protected void init() {
        super.init();
        int buttonsY = MINES_Y + 11;
        fewerButton = addRenderableWidget(CasinoButton.of(leftPos + LEFT_X + 2, topPos + buttonsY,
                NUDGE, NUDGE, Component.literal("-"), b -> pressMines(-1, b)));
        moreButton = addRenderableWidget(CasinoButton.of(
                leftPos + LEFT_X + CasinoLayout.GAUGE_W - NUDGE - 2, topPos + buttonsY,
                NUDGE, NUDGE, Component.literal("+"), b -> pressMines(1, b)));
        refreshWidgets();
    }

    // ------------------------------------------------------------------ state

    private boolean boardRunning() {
        GameState state = menu.gameState();
        return state == GameState.ROLLING || state == GameState.LOCKED || state == GameState.SETTLING;
    }

    private boolean canClick() {
        return menu.canAct() && menu.gameState() == GameState.ROLLING;
    }

    private int mines() {
        return mines.value();
    }

    @Override
    protected String wagerLabelKey() { return "itemcasino.button.start"; }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        boolean settable = menu.canAct() && menu.gameState().acceptsItems();
        if (fewerButton != null) fewerButton.active = settable && mines() > MineField.MIN_MINES;
        if (moreButton != null) moreButton.active = settable && mines() < MineField.MAX_MINES;
        if (actionButton != null && menu.gameState() == GameState.ROLLING && !menu.hasPayout()) {
            actionButton.active = menu.canAct();
            actionButton.setMessage(Component.translatable("itemcasino.button.cash_out",
                    InfoBadge.times(menu.multiplierPpm())));
        }
    }

    @Override
    protected void onAction() {
        if (menu.gameState() == GameState.ROLLING && !menu.hasPayout()) {
            if (!menu.canAct()) return;
            ClientPacketDistributor.sendToServer(new C2SMineAction(menu.containerId, menu.token(),
                    C2SMineAction.CASH_OUT, (byte) 0));
            return;
        }
        super.onAction();
    }

    /** One mine a click (five with Shift); held, it repeats and then moves by five. */
    private void pressMines(int direction, Button button) {
        if (!menu.canAct() || !menu.gameState().acceptsItems()) return;
        boolean shift = Minecraft.getInstance().hasShiftDown();
        mines.press(direction, shift ? 5 : 1, 5,
                () -> button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed());
    }

    @Override
    protected void containerTick() {
        mines.tick();
        if (pressedTile >= 0 && --pressedTicks <= 0) pressedTile = -1;
        for (int i = 0; i < flash.length; i++) if (flash[i] > 0) flash[i]--;
        if (boomTicks > 0) boomTicks--;
        replayChanges();
        super.containerTick();
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        mines.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        boolean overMines = x >= LEFT_X && x < LEFT_X + CasinoLayout.GAUGE_W
                && y >= MINES_Y && y < MINES_Y + 24;
        if (scrollY != 0 && overMines && menu.canAct() && menu.gameState().acceptsItems()) {
            mines.nudge(scrollY > 0 ? 1 : -1, Minecraft.getInstance().hasShiftDown() ? 5 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Sounds and flashes, from what the server's data says changed since the last tick. */
    private void replayChanges() {
        int revealed = menu.revealedMask();
        int outcome = menu.outcome();
        int token = menu.token();
        if (primed && token == seenToken) {
            int fresh = revealed & ~seenRevealed;
            if (fresh != 0) {
                for (int t = 0; t < MineField.TILES; t++) if ((fresh >>> t & 1) != 0) flash[t] = 6;
                CasinoSounds.safeTile(Integer.bitCount(revealed));
                pressedTile = -1;
            }
            if (outcome == MineFieldSession.OUTCOME_BOOM && seenOutcome != outcome) {
                boomTicks = 14;
                pressedTile = -1;
                CasinoSounds.boom();
            }
        }
        primed = true;
        seenRevealed = revealed;
        seenOutcome = outcome;
        seenToken = token;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && canClick()) {
            int tile = tileAt(event.x(), event.y());
            if (tile >= 0 && (menu.revealedMask() >>> tile & 1) == 0) {
                pressedTile = tile;
                pressedTicks = 20;
                ClientPacketDistributor.sendToServer(new C2SMineAction(menu.containerId, menu.token(),
                        C2SMineAction.REVEAL, (byte) tile));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The tile under a screen position, or -1 (the gaps between tiles count as no tile). */
    private int tileAt(double mouseX, double mouseY) {
        double x = mouseX - (leftPos + GRID_X);
        double y = mouseY - (topPos + GRID_Y);
        if (x < 0 || y < 0 || x >= GRID || y >= GRID) return -1;
        int column = (int) x / PITCH;
        int row = (int) y / PITCH;
        if ((int) x % PITCH >= TILE || (int) y % PITCH >= TILE) return -1;
        return row * MineField.SIDE + column;
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int revealed = menu.revealedMask();
        int mineMask = menu.mineMask();
        int outcome = menu.outcome();
        boolean running = boardRunning();
        boolean over = outcome != MineFieldSession.OUTCOME_NONE && !running;
        int hovered = canClick() ? tileAt(mouseX, mouseY) : -1;

        int shakeX = boomTicks > 8 ? (boomTicks % 2 == 0 ? 1 : -1) : 0;
        CasinoPanel.plate(graphics, leftPos + GRID_X - 3 + shakeX, topPos + GRID_Y - 3,
                GRID + 6, GRID + 6);

        for (int t = 0; t < MineField.TILES; t++) {
            int x = leftPos + GRID_X + shakeX + (t % MineField.SIDE) * PITCH;
            int y = topPos + GRID_Y + (t / MineField.SIDE) * PITCH;
            boolean safe = (revealed >>> t & 1) != 0;
            boolean mine = over && (mineMask >>> t & 1) != 0;
            if (safe) {
                drawSafe(graphics, x, y, flash[t] > 0);
            } else if (mine) {
                drawMine(graphics, x, y, outcome == MineFieldSession.OUTCOME_BOOM && t == menu.lastTile());
            } else {
                boolean live = running && menu.canAct();
                drawHidden(graphics, x, y, t == hovered && live, t == pressedTile, !running && !over
                        ? 0x50000000 : (over ? 0x70000000 : 0));
            }
        }
        if (boomTicks > 0) {
            int alpha = boomTicks * 9;
            graphics.fill(leftPos + GRID_X - 3, topPos + GRID_Y - 3, leftPos + GRID_X + GRID + 3,
                    topPos + GRID_Y + GRID + 3, (alpha << 24) | 0xE0503A);
        }
    }

    private static void drawHidden(GuiGraphics g, int x, int y, boolean hot, boolean pressed, int shade) {
        if (pressed) {
            g.fill(x, y, x + TILE, y + TILE, CasinoPanel.FRAME_EDGE);
            g.fill(x + 1, y + 1, x + TILE, y + TILE, CasinoPanel.FRAME_DEEP);
            return;
        }
        g.fill(x, y, x + TILE, y + TILE, CasinoPanel.FRAME_EDGE);
        g.fill(x, y, x + TILE - 1, y + TILE - 1, hot ? CasinoPanel.GOLD : CasinoPanel.FRAME_LIGHT);
        g.fill(x + 1, y + 1, x + TILE - 1, y + TILE - 1, hot ? 0xFF5E4430 : CasinoPanel.FRAME_MID);
        g.fill(x + 5, y + 5, x + 8, y + 8, hot ? CasinoPanel.GOLD_DEEP : CasinoPanel.FRAME_DEEP);
        if (shade != 0) g.fill(x, y, x + TILE, y + TILE, shade);
    }

    private static void drawSafe(GuiGraphics g, int x, int y, boolean fresh) {
        g.fill(x, y, x + TILE, y + TILE, CasinoPanel.FELT_EDGE);
        g.fill(x + 1, y + 1, x + TILE - 1, y + TILE - 1, fresh ? 0xFF3FA874 : 0xFF236B48);
        int cx = x + TILE / 2;
        int cy = y + TILE / 2;
        for (int dy = -3; dy <= 3; dy++) {
            int half = 3 - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, fresh ? 0xFFB8F5C8 : 0xFF7BD88F);
        }
        g.fill(cx - 1, cy - 2, cx, cy - 1, 0xFFE8FFF0);
    }

    private static void drawMine(GuiGraphics g, int x, int y, boolean exploded) {
        g.fill(x, y, x + TILE, y + TILE, CasinoPanel.FRAME_EDGE);
        g.fill(x + 1, y + 1, x + TILE - 1, y + TILE - 1, exploded ? 0xFFB02C2C : 0xFF3A2F3E);
        int cx = x + TILE / 2;
        int cy = y + TILE / 2 + 1;
        int[] halves = { 1, 2, 3, 3, 3, 2, 1 };
        for (int dy = -3; dy <= 3; dy++) {
            int half = halves[dy + 3];
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, 0xFF141014);
        }
        g.fill(cx - 2, cy - 2, cx - 1, cy - 1, 0xFF6B6058);
        g.fill(cx + 2, cy - 4, cx + 3, cy - 3, CasinoPanel.GOLD);
        g.fill(cx + 3, cy - 5, cx + 4, cy - 4, 0xFFF2E7CE);
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        // Left: what is staked, how many mines, and the chance the next tile is safe.
        gauge(graphics, LEFT_X, 22, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.stake"),
                worth(menu.stakeMilli()), CasinoPanel.TEXT_CREAM);

        CasinoPanel.plate(graphics, LEFT_X, MINES_Y, CasinoLayout.GAUGE_W, 24);
        graphics.drawString(font, Component.translatable("itemcasino.label.mines"), LEFT_X + 4,
                MINES_Y + 3, CasinoPanel.TEXT_MUTED, false);
        centred(graphics, Component.literal(Integer.toString(mines())),
                LEFT_X + CasinoLayout.GAUGE_W / 2, MINES_Y + 13, CasinoPanel.TEXT_LOSE);

        gauge(graphics, LEFT_X, 78, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.safe_next"),
                Component.literal(safeChance()), CasinoPanel.TEXT_WIN);

        // Right: the multiplier cashing out pays now, the next one, and what that is worth.
        int outcome = menu.outcome();
        boolean running = boardRunning();
        Component now;
        int nowColour;
        if (outcome == MineFieldSession.OUTCOME_BOOM && !running) {
            now = Component.literal("x0");
            nowColour = CasinoPanel.TEXT_LOSE;
        } else if (running || outcome == MineFieldSession.OUTCOME_CASHED) {
            now = Component.literal(InfoBadge.times(menu.multiplierPpm()));
            nowColour = CasinoPanel.TEXT_GOLD;
        } else {
            now = Component.literal("--");
            nowColour = CasinoPanel.TEXT_MUTED;
        }
        gauge(graphics, RIGHT_X, 22, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.cash_out"), now, nowColour);

        int next = menu.nextMultiplierPpm();
        gauge(graphics, RIGHT_X, 50, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.next_tile"),
                Component.literal(next > 0 ? InfoBadge.times(next) : "--"), CasinoPanel.TEXT_CREAM);

        long worth = -1;
        if (menu.stakeMilli() >= 0 && (running || outcome == MineFieldSession.OUTCOME_CASHED)) {
            worth = (long) menu.stakeMilli() * menu.multiplierPpm() / MineField.PPM;
        }
        gauge(graphics, RIGHT_X, 78, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.worth"),
                worth(worth), CasinoPanel.TEXT_GOLD);

        if (menu.canAct()) {
            Component status = status();
            int maxWidth = titleRightEdge() - (titleLabelX + font.width(title) + 8);
            rightAlignedFit(graphics, status, titleRightEdge(), titleLabelY, maxWidth,
                    statusColour());
        }
    }

    @Override
    protected Component infoText() {
        int edge = menu.edgePpm();
        return Component.translatable("itemcasino.info.mine_field",
                InfoBadge.percent(edge), InfoBadge.percent(MineField.PPM - edge));
    }

    /** The chance the next tile is safe, on the board in play or on a fresh one. */
    private String safeChance() {
        int k = boardRunning() ? Integer.bitCount(menu.revealedMask()) : 0;
        int remaining = MineField.TILES - k;
        int safe = remaining - mines();
        if (remaining <= 0 || safe <= 0) return "--";
        return InfoBadge.percent(1_000_000L * safe / remaining) + " %";
    }

    private Component worth(long milli) {
        // One chip is one point, so a thousandth of a point is a tenth of a cent.
        return milli < 0 ? Component.literal("--")
                : Component.literal(com.itemcasino.client.render.ChipText.format(milli / 10L));
    }

    /** A mine already makes its own noise. */
    @Override
    protected boolean playsLoseSound() {
        return false;
    }

    private boolean hasCard() {
        return com.itemcasino.chips.ChipCards.isCard(menu.ownWagerStack());
    }

    @Override
    protected boolean canWager() {
        return menu.canAct() && hasCard() && shownBet() >= 1;
    }

    private Component status() {
        int outcome = menu.outcome();
        if (boardRunning()) {
            return Component.translatable(menu.revealedMask() == 0
                    ? "itemcasino.label.mines_pick" : "itemcasino.label.mines_continue");
        }
        // Before the last board's result: without a card there is no next board, and saying so is
        // the one thing the player needs to read.
        if (!hasCard() && menu.gameState().acceptsItems()) {
            return Component.translatable("itemcasino.label.needs_card");
        }
        if (outcome == MineFieldSession.OUTCOME_BOOM) {
            return Component.translatable("itemcasino.label.mines_boom");
        }
        if (outcome == MineFieldSession.OUTCOME_CASHED) {
            return Component.translatable("itemcasino.label.mines_cashed",
                    InfoBadge.times(menu.multiplierPpm()));
        }
        if (menu.gameState() == GameState.ARMED) {
            return Component.translatable("itemcasino.label.mines_ready");
        }
        return Component.translatable("itemcasino.label.mines_hint");
    }

    private int statusColour() {
        int outcome = menu.outcome();
        if (!boardRunning() && !hasCard() && menu.gameState().acceptsItems()) return CasinoPanel.TEXT_PUSH;
        if (!boardRunning() && outcome == MineFieldSession.OUTCOME_BOOM) return CasinoPanel.TEXT_LOSE;
        if (!boardRunning() && outcome == MineFieldSession.OUTCOME_CASHED) return CasinoPanel.TEXT_GOLD;
        return CasinoPanel.TEXT_MUTED;
    }
}
