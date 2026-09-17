package com.itemcasino.client.screen;

import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.ChipText;
import com.itemcasino.client.render.ValueStepper;
import com.itemcasino.chips.ChipCards;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.network.c2s.C2SSetBet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import com.itemcasino.core.game.GameState;
import com.itemcasino.menu.AbstractCasinoMenu;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.LockableSlot;
import com.itemcasino.network.c2s.C2SAnimationComplete;
import com.itemcasino.network.c2s.C2SClaimPayout;
import com.itemcasino.network.c2s.C2SPlaceWager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Shared screen plumbing: the table, the action row, and the animation acknowledgement. */
public abstract class AbstractCasinoScreen<T extends AbstractCasinoMenu>
        extends AbstractContainerScreen<T> {

    protected Button actionButton;
    /** Ticks the commit button stays dead after a result, so a click meant for the win starts nothing. */
    private static final int ACTION_LOCK_TICKS = 24;
    private int actionLock;
    /** Where every table's (i) sits, panel-relative: the top right corner of the window. */
    public static final int INFO_X = CasinoLayout.WIDTH - 7 - com.itemcasino.client.render.InfoBadge.SIZE;
    public static final int INFO_Y = 5;
    /** The celebration of a win, over the felt. */
    protected final com.itemcasino.client.render.WinBanner banner = new com.itemcasino.client.render.WinBanner();

    // The chip bet, in its own column to the left of the table window, panel-relative (x < 0).
    private static final int BET_W = 50;
    private static final int BET_GAP = 4;
    private static final int BET_Y = CasinoLayout.FELT_Y;
    private static final int BET_H = 170;
    private static final int BET_BUTTON_H = 11;
    private static final int BET_MORE_Y = BET_Y + 27;
    private static final int BET_TRACK_Y = BET_Y + 42;
    private static final int BET_TRACK_H = 70;
    private static final int BET_LESS_Y = BET_TRACK_Y + BET_TRACK_H + 4;
    private static final int BET_ROW_Y = BET_LESS_Y + 14;
    private static final int BET_KNOB_H = 5;
    private boolean draggingBet;
    private Button betHalf;
    private Button betLess;
    private Button betMore;
    private Button betMax;
    private ValueStepper bet;

    protected AbstractCasinoScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = CasinoLayout.WIDTH;
        this.imageHeight = CasinoLayout.HEIGHT;
        this.titleLabelX = 9;
        this.titleLabelY = 7;
        this.inventoryLabelX = CasinoLayout.INV_X;
        this.inventoryLabelY = CasinoLayout.INV_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        actionButton = addRenderableWidget(CasinoButton.of(
                leftPos + primaryButtonX(), topPos + CasinoLayout.ROW_Y,
                primaryButtonWidth(), CasinoLayout.BUTTON_H,
                Component.translatable("itemcasino.button.wager"), button -> onAction()));
        bet = new ValueStepper(() -> 1, () -> clampInt(betCeiling()), () -> clampInt(menu.betChips()), this::sendBet);
        int column = betColumnX();
        betMore = addRenderableWidget(CasinoButton.of(column + 4, topPos + BET_MORE_Y, BET_W - 8, BET_BUTTON_H,
                Component.literal("+"), b -> pressBet(1, b)));
        betLess = addRenderableWidget(CasinoButton.of(column + 4, topPos + BET_LESS_Y, BET_W - 8, BET_BUTTON_H,
                Component.literal("-"), b -> pressBet(-1, b)));
        betHalf = addRenderableWidget(CasinoButton.of(column + 4, topPos + BET_ROW_Y, 20, BET_BUTTON_H,
                Component.literal("1/2"), b -> bet.set(Math.max(1, bet.value() / 2))));
        betMax = addRenderableWidget(CasinoButton.of(column + 26, topPos + BET_ROW_Y, 20, BET_BUTTON_H,
                Component.translatable("itemcasino.bet.max"), b -> bet.set(clampInt(betCeiling()))));
        refreshWidgets();
    }

    // ------------------------------------------------------------------ the chip bet

    /** This player's slot holds a chip card they may bet with. */
    protected boolean betBarShown() {
        return menu.canAct() && ChipCards.isCard(menu.ownWagerStack());
    }

    /**
     * The column stays up, read-only, while a chip bet is being played: the card is in escrow then,
     * and a column that vanished at every commit and came back at every result would jump about.
     */
    private boolean betColumnShown() {
        return betBarShown() || (menu.canAct() && !menu.gameState().acceptsItems()
                && ChipCards.isCard(ClientSessionState.wagerGhost()));
    }

    /** The card the column describes: the one in the slot, or the one in play. */
    private ItemStack betCard() {
        ItemStack slot = menu.ownWagerStack();
        return ChipCards.isCard(slot) ? slot : ClientSessionState.wagerGhost();
    }

    /** Whole chips on the card in this player's slot. */
    protected long cardChips() {
        return ChipCards.wholeChips(menu.ownWagerStack());
    }

    /** The most the column lets the player ask for: the card, and the table's own limit. */
    private long betCeiling() {
        return Math.max(1, Math.min(cardChips(), tableMaxBet()));
    }

    private static int clampInt(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    /** A table's limit on one chip bet, when the screen knows it; the server enforces it anyway. */
    protected long tableMaxBet() { return menu.maxBetChips(); }

    /** The bet this table will actually take: the setting, capped by the card and the table. */
    protected long shownBet() {
        if (!menu.gameState().acceptsItems()) return menu.betChips();
        return Math.max(0, Math.min(bet == null ? menu.betChips() : bet.value(), betCeiling()));
    }

    private void sendBet(int chips) {
        ClientPacketDistributor.sendToServer(new C2SSetBet(menu.containerId, chips));
    }

    private void pressBet(int direction, Button button) {
        if (!menu.gameState().acceptsItems()) return;
        boolean shift = Minecraft.getInstance().hasShiftDown();
        int fast = (int) Math.max(10, Math.min(Integer.MAX_VALUE, cardChips() / 20));
        bet.press(direction, shift ? 10 : 1, shift ? Math.max(10, fast) : fast,
                () -> button.isHovered() && Minecraft.getInstance().mouseHandler.isLeftPressed());
    }

    /** The column's left edge on screen: beside the window, or pressed against the screen edge if there is no room. */
    private int betColumnX() {
        return Math.max(2, leftPos - BET_GAP - BET_W);
    }

    private boolean overBetColumn(double mouseX, double mouseY) {
        int x = betColumnX();
        return mouseX >= x && mouseX < x + BET_W && mouseY >= topPos + BET_Y && mouseY < topPos + BET_Y + BET_H;
    }

    private boolean overBetTrack(double mouseX, double mouseY) {
        double centre = betColumnX() + BET_W / 2.0;
        return Math.abs(mouseX - centre) <= 8 && mouseY >= topPos + BET_TRACK_Y - 3
                && mouseY <= topPos + BET_TRACK_Y + BET_TRACK_H + 3;
    }

    /**
     * Track position (0 at the bottom, 1 at the top) to a bet. Logarithmic, so a card of a hundred
     * thousand chips still leaves room to pick ten: every tenfold takes the same length of track.
     */
    static long betAt(double t, long max) {
        if (max <= 1) return 1;
        t = Math.max(0, Math.min(1, t));
        if (t >= 0.995) return max;
        long raw = Math.round(Math.exp(t * Math.log(max)));
        if (raw >= 100) {
            // Two significant figures while dragging: 1 200 rather than 1 187.
            long unit = (long) Math.pow(10, (long) Math.log10(raw) - 1);
            raw = Math.round(raw / (double) unit) * unit;
        }
        return Math.max(1, Math.min(max, raw));
    }

    static double trackOf(long bet, long max) {
        if (max <= 1) return 1;
        return Math.max(0, Math.min(1, Math.log(Math.max(1, bet)) / Math.log(max)));
    }

    private void dragBetTo(double mouseY) {
        double t = 1 - (mouseY - (topPos + BET_TRACK_Y)) / BET_TRACK_H;
        bet.set(clampInt(betAt(t, betCeiling())));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && betBarShown() && menu.gameState().acceptsItems()
                && overBetTrack(event.x(), event.y())) {
            draggingBet = true;
            dragBetTo(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingBet) {
            if (betBarShown() && menu.gameState().acceptsItems()) dragBetTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0 && betBarShown() && menu.gameState().acceptsItems() && overBetColumn(mouseX, mouseY)) {
            bet.nudge(scrollY > 0 ? 1 : -1, Minecraft.getInstance().hasShiftDown() ? 10 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (bet != null) bet.release();
        if (draggingBet) {
            draggingBet = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    /** A click on the column is not a click outside the window: it must not throw the carried stack away. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return super.hasClickedOutside(mouseX, mouseY, left, top)
                && !(betColumnShown() && overBetColumn(mouseX, mouseY));
    }

    // ------------------------------------------------------------------ the reveal

    /**
     * Whether this table has finished showing its result, so the payout may be announced. The spin
     * state already holds a reveal back while a wheel or reel is turning; a table with an animation
     * of its own (blackjack's cards) says so here.
     */
    protected boolean revealReady() {
        return true;
    }

    /** A settled wager, revealed: the banner and fanfare for the winner, a low note for the loser. */
    protected void onReveal(ClientSessionState.Reveal reveal) {
        int seat = menu.seatIndex();
        if (seat < 0) return;                      // spectators watch the table, not the payout
        actionLock = ACTION_LOCK_TICKS;
        if (actionButton != null) actionButton.active = false;
        if (reveal.winnerSeat() == seat && reveal.tier() > 0) {
            Component amount = reveal.winCents() > 0
                    ? Component.translatable("itemcasino.banner.chips", ChipText.format(reveal.winCents()))
                    : Component.empty();
            banner.show(reveal.tier(), amount, reveal.stacks());
            if (reveal.tier() >= com.itemcasino.network.s2c.S2CPayoutReady.TIER_JACKPOT) {
                com.itemcasino.client.CasinoSounds.jackpot();
            } else {
                com.itemcasino.client.CasinoSounds.win(reveal.tier() >= com.itemcasino.network.s2c.S2CPayoutReady.TIER_BIG);
            }
            return;
        }
        boolean lost = reveal.winnerSeat() >= 0 || reveal.tier() == com.itemcasino.network.s2c.S2CPayoutReady.TIER_NONE;
        if (lost && playsLoseSound()) com.itemcasino.client.CasinoSounds.lose();
    }

    /** The table's rules, behind the (i) in the top right corner of every table; null for none. */
    @javax.annotation.Nullable
    protected Component infoText() {
        return null;
    }

    /** Where text in the title bar must stop on the right, clear of the (i). */
    protected int titleRightEdge() {
        return infoText() != null ? INFO_X - 4 : CasinoLayout.WIDTH - 9;
    }

    /**
     * On a table that plays chips only, the empty wager slot shows a faded chip card and says, on
     * hover, what it wants and where to get one. Nothing else on such a table explains why an item
     * dropped on the slot just bounces off.
     */
    private void renderChipCardHint(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.chipsOnly() || !menu.canAct() || !menu.gameState().acceptsItems()) return;
        int index = menu.ownWagerSlot();
        if (index < 0 || index >= menu.slots.size()) return;
        Slot slot = menu.slots.get(index);
        if (slot.hasItem()) return;
        int x = leftPos + slot.x;
        int y = topPos + slot.y;
        graphics.renderItem(new ItemStack(com.itemcasino.registry.CasinoItems.CHIP_CARD.get()), x, y);
        graphics.fill(x, y, x + 16, y + 16, 0xAA1D2A22);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16 && menu.getCarried().isEmpty()) {
            graphics.setTooltipForNextFrame(font, font.split(
                    Component.translatable("itemcasino.hint.chip_card_slot"), 170), mouseX, mouseY);
        }
    }

    /** A table whose loss already makes its own noise (a mine) says so, rather than playing two. */
    protected boolean playsLoseSound() {
        return true;
    }

    /** Where the banner is centred, panel-relative: the middle of the felt. */
    protected int bannerCentreY() {
        return CasinoLayout.FELT_Y + CasinoLayout.FELT_H / 2 - 8;
    }

    /** Where the commit button starts; blackjack narrows it to make room for its own row. */
    protected int primaryButtonX() {
        return CasinoLayout.BUTTONS_X;
    }

    protected int primaryButtonWidth() {
        return CasinoLayout.BUTTONS_W;
    }

    /** SPIN while armed, CLAIM once there is something to collect. */
    protected void onAction() {
        if (actionLocked()) return;
        if (menu.hasPayout()) {
            ClientPacketDistributor.sendToServer(
                    new C2SClaimPayout(menu.containerId, ClientSessionState.sessionId()));
        } else if (menu.gameState() == GameState.ARMED) {
            // Captured before the packet goes out, while the stack is still in the slot: the
            // server empties it on commit, and an empty slot with no explanation reads as "the
            // game ate my item".
            ClientSessionState.rememberWager(stakeTakenFrom(menu.ownWagerStack()));
            ClientPacketDistributor.sendToServer(new C2SPlaceWager(menu.containerId));
        }
    }

    /**
     * What a commit will take out of this stack, for the ghost drawn in the sealed slot. All of it,
     * except at a table that takes only part (the slot machine's stake ceiling).
     */
    protected ItemStack stakeTakenFrom(ItemStack slot) {
        return slot;
    }

    protected void refreshWidgets() {

        if (betHalf != null) {
            boolean shown = betColumnShown();
            boolean live = betBarShown() && menu.gameState().acceptsItems();
            for (Button button : new Button[] { betHalf, betLess, betMore, betMax }) {
                button.visible = shown;
                button.active = live;
            }
            if (live) {
                betLess.active = bet.value() > 1;
                betMore.active = bet.value() < betCeiling();
            }
        }
        if (actionButton == null) return;
        boolean payout = menu.hasPayout();
        boolean armed = menu.gameState() == GameState.ARMED && canWager();
        if (!menu.canAct()) { actionButton.active = false; return; }
        actionButton.active = payout || armed;
        actionButton.setMessage(Component.translatable(
                payout ? "itemcasino.button.claim" : wagerLabelKey()));
    }

    /** Subclasses veto the wager button when their own preconditions are unmet. */
    protected boolean canWager() {
        return true;
    }

    /** Label for the commit button; blackjack calls it "Deal" rather than "Spin". */
    protected String wagerLabelKey() {
        return "itemcasino.button.wager";
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (bet != null) bet.tick();
        ClientSessionState.tick();
        banner.tick();
        if (revealReady()) {
            ClientSessionState.Reveal reveal = ClientSessionState.consumeReveal();
            if (reveal != null) onReveal(reveal);
        }

        // Purely an optimisation: the server settles on its own deadline whether or not this
        // arrives, so a dropped packet costs a second of latency and nothing else.
        if (ClientSessionState.consumeAcknowledgement()) {
            ClientPacketDistributor.sendToServer(new C2SAnimationComplete(
                    menu.containerId, ClientSessionState.sessionId()));
        }
        refreshWidgets();
        if (actionLock > 0) {
            actionLock--;
            if (actionButton != null) actionButton.active = false;
        }
    }

    /** True while a result has just been shown and the commit button is held dead. */
    protected boolean actionLocked() {
        return actionLock > 0;
    }

    // ------------------------------------------------------------------ painting

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        CasinoPanel.background(graphics, leftPos, topPos);
        if (betColumnShown()) renderBetColumn(graphics);
        // Every wager slot gets its gold well, wherever the menu put it -- one for the solo games,
        // two facing each other across the duel table.
        for (Slot slot : menu.slots) {
            if (slot instanceof LockableSlot) {
                CasinoPanel.goldWell(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        renderTable(graphics, partialTick, mouseX, mouseY);
    }

    protected abstract void renderTable(GuiGraphics graphics, float partialTick,
                                        int mouseX, int mouseY);

    /**
     * Both labels are drawn here rather than by the superclass, which paints them in the dark grey
     * that suits a stone-grey inventory and is all but invisible on baize.
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, CasinoPanel.TEXT_GOLD, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                CasinoPanel.TEXT_MUTED, false);
        if (betColumnShown()) {
            // Panel-relative like everything drawn here, so the column's x is negative.
            int centre = betColumnX() - leftPos + BET_W / 2;
            int width = BET_W - 6;
            centredFit(graphics, Component.translatable("itemcasino.bet.caption"), centre, BET_Y + 5, width,
                    CasinoPanel.TEXT_MUTED);
            centredFit(graphics, Component.literal(ChipText.format(Chips.centsOfChips(shownBet()))), centre,
                    BET_Y + 15, width, CasinoPanel.TEXT_GOLD);
            centredFit(graphics, Component.translatable("itemcasino.bet.card"), centre, BET_ROW_Y + 16, width,
                    CasinoPanel.TEXT_MUTED);
            centredFit(graphics, Component.literal(ChipText.format(ChipCards.balance(betCard()))), centre,
                    BET_ROW_Y + 26, width, CasinoPanel.TEXT_CREAM);
        }
        renderSpectatorNotice(graphics);
        renderReadouts(graphics);
    }

    /** The column's plate, and its track with the knob at the bet: absolute coordinates, under the buttons. */
    private void renderBetColumn(GuiGraphics graphics) {
        int x = betColumnX();
        int y = topPos + BET_Y;
        graphics.fill(x + 3, y + 3, x + BET_W + 3, y + BET_H + 3, 0x55000000);
        CasinoPanel.rounded(graphics, x, y, BET_W, BET_H, CasinoPanel.FRAME_EDGE);
        CasinoPanel.rounded(graphics, x + 1, y + 1, BET_W - 2, BET_H - 2, CasinoPanel.FRAME_LIGHT);
        CasinoPanel.rounded(graphics, x + 2, y + 2, BET_W - 4, BET_H - 4, CasinoPanel.FRAME_MID);
        CasinoPanel.plate(graphics, x + 4, y + 3, BET_W - 8, 22);

        boolean live = betBarShown() && menu.gameState().acceptsItems();
        int centre = x + BET_W / 2;
        int top = topPos + BET_TRACK_Y;
        graphics.fill(centre - 3, top - 1, centre + 3, top + BET_TRACK_H + 1, CasinoPanel.FRAME_EDGE);
        graphics.fill(centre - 2, top, centre + 2, top + BET_TRACK_H, 0xFF1D2A22);
        long max = betCeiling();
        double t = trackOf(shownBet(), max);
        int knob = top + BET_TRACK_H - (int) Math.round(t * BET_TRACK_H);
        // The part of the track below the knob is the bet: filled gold, like a gauge.
        graphics.fill(centre - 1, knob, centre + 1, top + BET_TRACK_H, live ? CasinoPanel.GOLD_DEEP : 0xFF4A4238);
        int knobTop = Math.max(top - 2, Math.min(top + BET_TRACK_H - BET_KNOB_H + 2, knob - BET_KNOB_H / 2));
        CasinoPanel.rounded(graphics, centre - 7, knobTop, 14, BET_KNOB_H, CasinoPanel.FRAME_EDGE);
        graphics.fill(centre - 6, knobTop + 1, centre + 6, knobTop + BET_KNOB_H - 1,
                live ? (draggingBet ? CasinoPanel.TEXT_GOLD : CasinoPanel.GOLD) : 0xFF6B6058);
    }

    /** Each game's own text, drawn in panel-relative coordinates. */
    protected void renderReadouts(GuiGraphics graphics) {}

    /** How many times the committed stack is actually at stake. Two on a doubled blackjack hand. */
    protected int ghostMultiplier() { return 1; }

    /**
     * Drawn after the slots and their items. {@code renderBg} runs underneath them, so anything
     * that must sit on top -- a picker panel, a result banner -- belongs here instead.
     */
    protected void renderOverlay(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        renderEscrowGhost(graphics);
    }

    /**
     * Draws the committed stack, dimmed, in the sealed wager slot.
     *
     * <p>The slot really is empty on both sides — the wager lives in the session's escrow — so
     * without this the player watches their items vanish the instant they press the button and has
     * no way to tell a live wager from a bug. The position comes from the menu's own slot, so it
     * stays correct whatever each table's layout is.
     */
    protected void renderEscrowGhost(GuiGraphics graphics) {
        if (menu.gameState().acceptsItems()) return;
        ItemStack ghost = ClientSessionState.wagerGhost();
        if (ghost.isEmpty() || menu.slots.isEmpty()) return;

        int index = menu.ownWagerSlot();
        if (index < 0 || index >= menu.slots.size()) return;
        int x = leftPos + menu.slots.get(index).x;
        int y = topPos + menu.slots.get(index).y;

        // A game can have more at stake than what was put in the slot -- a doubled blackjack hand
        // draws its collateral straight from the inventory -- so the ghost is drawn at the real
        // stake rather than at the size of the stack that started it.
        int multiplier = Math.max(1, ghostMultiplier());
        if (multiplier > 1) {
            ghost = ghost.copy();
            ghost.setCount(Math.min(ghost.getMaxStackSize() * 4, ghost.getCount() * multiplier));
        }
        graphics.renderItem(ghost, x, y);
        graphics.renderItemDecorations(font, ghost, x, y);
        graphics.fill(x, y, x + 16, y + 16, 0x99101018);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderOverlay(graphics, partialTick, mouseX, mouseY);
        Component info = infoText();
        if (info != null) {
            com.itemcasino.client.render.InfoBadge.draw(graphics, font, leftPos + INFO_X, topPos + INFO_Y,
                    mouseX, mouseY, info);
        }
        renderChipCardHint(graphics, mouseX, mouseY);
        banner.render(graphics, font, leftPos + CasinoLayout.CENTRE_X, topPos + bannerCentreY(), partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        banner.hide();
        ClientSessionState.reset();
        super.onClose();
    }

    // ------------------------------------------------------------------ text helpers

    /** Centred text; alpha must be non-zero since 1.21.6 or nothing is drawn. */
    protected void centred(GuiGraphics graphics, Component text, int centreX, int y, int argb) {
        graphics.drawString(font, text, centreX - font.width(text) / 2, y, argb, false);
    }

    /** Centred text squeezed to fit a width, rather than spilling off the felt in a long translation. */
    protected void centredFit(GuiGraphics graphics, Component text, int centreX, int y, int maxWidth,
                              int argb) {
        fitted(graphics, text, centreX, y, maxWidth, argb, 0.5F);
    }

    /** Left-aligned text squeezed to fit a width. */
    protected void leftFit(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int argb) {
        fitted(graphics, text, x, y, maxWidth, argb, 0F);
    }

    /** Right-aligned text squeezed to fit a width. */
    protected void rightAlignedFit(GuiGraphics graphics, Component text, int rightX, int y,
                                   int maxWidth, int argb) {
        fitted(graphics, text, rightX, y, maxWidth, argb, 1F);
    }

    /** @param anchor 0 draws from {@code x}, 0.5 centres on it, 1 ends at it */
    private void fitted(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int argb,
                        float anchor) {
        int width = font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(font, text, x - Math.round(width * anchor), y, argb, false);
            return;
        }
        float scale = maxWidth / (float) width;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x - maxWidth * anchor, y + (1 - scale) * font.lineHeight / 2F);
        graphics.pose().scale(scale, scale);
        graphics.drawString(font, text, 0, 0, argb, false);
        graphics.pose().popMatrix();
    }

    /** Right-aligned text, for the gauge on the right-hand side of the felt. */
    protected void rightAligned(GuiGraphics graphics, Component text, int rightX, int y, int argb) {
        graphics.drawString(font, text, rightX - font.width(text), y, argb, false);
    }

    /**
     * A caption over a value, on a recessed plate: the read-out unit both wheel games use to keep
     * their numbers out of the centrepiece instead of on top of it.
     */
    protected void gauge(GuiGraphics graphics, int x, int y, int width, Component caption,
                         Component value, int valueColour) {
        CasinoPanel.plate(graphics, x, y, width, 24);
        // Squeezed rather than spilled: "Si vous gagnez" is wider than a gauge, and text running
        // off its plate over the dial is what a French screen looked like before.
        leftFit(graphics, caption, x + 4, y + 4, width - 8, CasinoPanel.TEXT_MUTED);
        leftFit(graphics, value, x + 4, y + 14, width - 8, valueColour);
    }

    /** Tells a spectator why nothing responds to them. */
    protected void renderSpectatorNotice(GuiGraphics graphics) {
        if (menu.canAct()) return;
        rightAligned(graphics, Component.translatable("itemcasino.label.spectating"),
                titleRightEdge(), titleLabelY, 0xFFD0A040);
    }
}
