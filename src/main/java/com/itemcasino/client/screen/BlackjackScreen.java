package com.itemcasino.client.screen;

import com.itemcasino.client.ClientBlackjackState;
import com.itemcasino.client.CasinoSounds;
import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.render.CardRenderer;
import com.itemcasino.core.game.blackjack.Outcome;
import com.itemcasino.menu.BlackjackMenu;
import com.itemcasino.session.BlackjackSession;
import com.itemcasino.network.s2c.SeatHand;
import net.minecraft.client.resources.language.I18n;
import com.itemcasino.client.render.CasinoButton;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.blackjack.BlackjackAction;
import com.itemcasino.core.game.blackjack.BlackjackPhase;
import com.itemcasino.core.game.blackjack.Card;
import com.itemcasino.core.game.blackjack.Outcome;
import com.itemcasino.menu.BlackjackMenu;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.network.c2s.C2SBlackjackAction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The blackjack table.
 *
 * <p>The four action buttons are enabled from the mask the server sent. That mask is a courtesy:
 * pressing a button the server considers illegal simply does nothing, because the server re-derives
 * legality from its own hand on receipt.
 *
 * <p>The hand is drawn from {@link ClientBlackjackState}'s deal clock rather than straight from the
 * last packet, so the cards arrive one at a time from the shoe instead of appearing as a finished
 * row. The buttons stay hidden until the last card has landed — pressing Hit while cards are still
 * flying would be legal, but it reads as the table jumping ahead of itself.
 */
public class BlackjackScreen extends AbstractCasinoScreen<BlackjackMenu> {

    private static final int DEALER_Y = 20;
    private static final int PLAYER_Y = 58;
    private static final int HAND_X = 62;
    /** Stops short of the shoe in the corner, so a long hand never runs underneath it. */
    private static final int HAND_SPACE = CasinoLayout.SHOE_X - HAND_X - 5;
    private static final int STATUS_Y = 96;
    /** Under each hand: who is holding it and what it comes to. */
    private static final int SEAT_LABEL_Y = 94;
    /**
     * Three hands need the whole felt, so at a shared table they start where the gauges would be
     * and run to the shoe: 10 to 176, three columns of 55. Fifty-five is not a round number, it is
     * the width of five fanned cards (24 for the first, then four at the minimum 7), which is the
     * longest hand anybody plays. The "You" gauge goes, because at a shared table your total is
     * written under your own cards along with everyone else's.
     */
    private static final int SHARED_HAND_X = 10;
    private static final int SHARED_COLUMN = 55;

    private final Map<BlackjackAction, Button> actionButtons = new EnumMap<>(BlackjackAction.class);

    public BlackjackScreen(BlackjackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected String wagerLabelKey() {
        // With someone else at the table the button does not deal, it says you are ready to play
        // what is in your box: the dealer deals when everyone looking has said so, or when the
        // countdown runs out. Alone, it deals, as it always has.
        if (menu.seatCount() <= 1) return "itemcasino.button.deal";
        int own = menu.seatIndex();
        int others = menu.readout(BlackjackSession.READOUT_PRESENT) & ~(1 << own);
        if (others == 0) return "itemcasino.button.deal";
        // Once your chair is ready, the same button takes it back, and says so.
        boolean mineReady = own >= 0 && (menu.readout(BlackjackSession.READOUT_READY) & (1 << own)) != 0;
        return mineReady ? "itemcasino.button.not_ready" : "itemcasino.button.ready";
    }

    @Override
    protected void init() {
        super.init();
        actionButtons.clear();
        BlackjackAction[] actions = BlackjackAction.values();
        int width = CasinoLayout.buttonWidth(actions.length);
        for (int i = 0; i < actions.length; i++) {
            BlackjackAction action = actions[i];
            Button button = addRenderableWidget(CasinoButton.of(
                    leftPos + CasinoLayout.buttonX(i, actions.length),
                    topPos + CasinoLayout.ROW_Y, width, CasinoLayout.BUTTON_H,
                    Component.translatable("itemcasino.button." + action.name().toLowerCase(Locale.ROOT)),
                    b -> send(action)));
            actionButtons.put(action, button);
        }
        refreshWidgets();
    }

    private void send(BlackjackAction action) {
        ClientPacketDistributor.sendToServer(new C2SBlackjackAction(
                menu.containerId, ClientSessionState.sessionId(), (byte) action.ordinal()));
    }

    @Override
    protected void containerTick() {
        if (ClientBlackjackState.tick()) CasinoSounds.card();
        // Someone has said they are ready for the next hand: the last one comes off the felt now,
        // so the countdown is on screen for the whole of it.
        if (menu.seatCount() > 1 && menu.readout(BlackjackSession.READOUT_COUNTDOWN) > 0) {
            ClientBlackjackState.hurrySweep();
        }
        super.containerTick();
        // The server holds the payout until the hand has been shown; this says it has. Its own
        // deadline pays anyway if this never arrives, so it is only ever a way to be quicker.
        if (ClientBlackjackState.consumeAcknowledgement()) {
            ClientPacketDistributor.sendToServer(new com.itemcasino.network.c2s.C2SAnimationComplete(
                    menu.containerId, ClientBlackjackState.handSession()));
        }
    }

    /** The payout is revealed once the last card is down and the result is on the felt. */
    @Override
    protected boolean revealReady() {
        return ClientBlackjackState.outcome() == null || ClientBlackjackState.resultShown();
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        boolean handInPlay = menu.canAct() && menu.gameState() == GameState.ROLLING
                && ClientBlackjackState.phase() == BlackjackPhase.PLAYER_TURN;
        boolean ready = handInPlay && ClientBlackjackState.allPlaced();
        int mask = ClientBlackjackState.legalMask();

        for (Map.Entry<BlackjackAction, Button> entry : actionButtons.entrySet()) {
            Button button = entry.getValue();
            button.visible = handInPlay;
            button.active = ready && entry.getKey().isIn(mask);
        }
        if (actionButton != null) {
            actionButton.visible = !handInPlay;
            // Nothing is collectable while cards are still landing; the server holds the payout back
            // until then anyway, this only keeps the button from flickering.
            if (ClientBlackjackState.outcome() != null && !ClientBlackjackState.resultShown()) {
                actionButton.active = false;
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected int ghostMultiplier() {
        return Math.max(1, menu.option());
    }

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // The client has no idea which chair it is in until its own menu says so.
        ClientBlackjackState.ownSeat(Math.max(0, menu.seatIndex()));
        int shoeX = leftPos + CasinoLayout.SHOE_X;
        int shoeY = topPos + CasinoLayout.SHOE_Y;
        shoe(graphics, shoeX, shoeY);

        // A finished hand is swept off the felt to the left, cards and labels together, picking up
        // speed as it goes; the felt's edge cuts them off, so they leave the table rather than slide
        // over the frame.
        float sweep = ClientBlackjackState.sweepProgress(partialTick);
        if (sweep >= 0F) {
            graphics.enableScissor(leftPos + CasinoLayout.FELT_X, topPos + CasinoLayout.FELT_Y,
                    leftPos + CasinoLayout.FELT_X + CasinoLayout.FELT_W,
                    topPos + CasinoLayout.FELT_Y + CasinoLayout.FELT_H);
            graphics.pose().pushMatrix();
            graphics.pose().translate(-sweep * sweep * (CasinoLayout.FELT_W + CardRenderer.CARD_WIDTH), 0F);
        }
        drawHands(graphics, partialTick, shoeX, shoeY);
        if (sweep >= 0F) {
            graphics.pose().popMatrix();
            graphics.disableScissor();
        }
    }

    /** Every card on the felt and the line under each hand. */
    private void drawHands(GuiGraphics graphics, float partialTick, int shoeX, int shoeY) {
        List<Card> dealer = ClientBlackjackState.dealerVisible();
        List<Card> player = ClientBlackjackState.playerHand();
        boolean holeHidden = ClientBlackjackState.holeHidden();
        int dealerSlots = ClientBlackjackState.dealerOnTable();

        int dealerSpacing = CardRenderer.spacingFor(dealerSlots, HAND_SPACE);
        for (int i = 0; i < dealerSlots; i++) {
            float age = ClientBlackjackState.placementAge(true, i, partialTick);
            if (age < 0F) continue;
            int x = leftPos + HAND_X + i * dealerSpacing;
            int y = topPos + DEALER_Y;

            float flip = ClientBlackjackState.holeRevealAge(partialTick);
            if (i == 1 && (holeHidden || (flip < 0F && ClientBlackjackState.holeWaitingToTurn()))) {
                // Face down: still hidden on the server, or shown there but not yet turned here.
                CardRenderer.drawDealtBack(graphics, x, y, shoeX, shoeY, age,
                        ClientBlackjackState.SLIDE_TICKS);
            } else if (i == 1 && flip >= 0F && i < dealer.size()) {
                // The card that was face down turns over where it lies rather than being re-dealt.
                CardRenderer.drawFlip(graphics, dealer.get(i), x, y, flip, ClientBlackjackState.FLIP_TICKS);
            } else if (i < dealer.size()) {
                CardRenderer.drawDealt(graphics, dealer.get(i), x, y, shoeX, shoeY, age,
                        ClientBlackjackState.SLIDE_TICKS);
            }
        }

        boolean shared = menu.seatCount() > 1;
        int[] places = BlackjackMenu.seatsAround(menu.seatIndex(), menu.seatCount());
        int ownX = shared ? placeX(1) : HAND_X;
        int ownSpace = shared ? SHARED_COLUMN : HAND_SPACE;

        int playerSpacing = CardRenderer.spacingFor(player.size(), ownSpace);
        for (int i = 0; i < player.size(); i++) {
            float age = ClientBlackjackState.placementAge(false, i, partialTick);
            if (age < 0F) continue;
            CardRenderer.drawDealt(graphics, player.get(i),
                    leftPos + ownX + i * playerSpacing, topPos + PLAYER_Y,
                    shoeX, shoeY, age, ClientBlackjackState.SLIDE_TICKS);
        }

        if (shared) {
            drawNeighbours(graphics, places);
            SeatHand mine = ClientBlackjackState.handOf(menu.seatIndex());
            if (mine != null) seatLabel(graphics, leftPos + ownX, ownSpace, menu.seatIndex(), mine);
        }
    }

    /**
     * Where one of the three places starts on the felt.
     *
     * <p>The player row is split into three columns — the neighbours either side, the viewer in the
     * middle — rather than given plates of their own somewhere else. There is nowhere else: once the
     * dealer's row, the gauges, the status line and the action row have had their share of 202
     * pixels, the only space left for a hand is where a hand already goes. Splitting it is also
     * simply what a table looks like from a chair.
     */
    private int placeX(int place) {
        return SHARED_HAND_X + place * SHARED_COLUMN;
    }

    /**
     * The neighbours' hands, in their columns, with their name and total underneath.
     *
     * <p>Their cards are laid down without the deal animation: the clock follows this client's own
     * hand, and a neighbour's cards simply being there is the honest picture anyway — at a table you
     * look up and they are already holding them.
     */
    private void drawNeighbours(GuiGraphics graphics, int[] places) {
        for (int place = 0; place < 3; place += 2) {
            int seat = places[place];
            if (seat < 0 || seat == menu.seatIndex()) continue;
            SeatHand hand = ClientBlackjackState.handOf(seat);
            if (hand == null) continue;

            int x = leftPos + placeX(place);
            int width = SHARED_COLUMN;
            int spacing = CardRenderer.spacingFor(hand.cards().size(), width);
            for (int i = 0; i < hand.cards().size(); i++) {
                CardRenderer.drawCard(graphics, hand.cards().get(i), x + i * spacing,
                        topPos + PLAYER_Y);
            }

            seatLabel(graphics, x, width, seat, hand);
        }
    }

    /**
     * One line under a hand: who it belongs to, and what it comes to.
     *
     * <p>Gold means the table is waiting on them. Once the hand is settled the total takes the
     * colour of the result, which is how you read a table at a glance rather than by reading words
     * that would not fit in fifty-five pixels anyway.
     */
    private void seatLabel(GuiGraphics graphics, int x, int width, int seat, SeatHand hand) {
        boolean theirTurn = ClientBlackjackState.turn() == seat;
        String reading = hand.total() + (hand.betUnits() > 1 ? " x2" : "");
        if (theirTurn) {
            int seconds = ClientBlackjackState.deadlineTicks() / 20;
            // Only once it is short enough to matter, and beside the name of whoever it is running
            // out on, which is the only place it means anything at a table with three chairs.
            if (seconds <= 10) reading = seconds + "s " + reading;
        }
        int readingColour = CasinoPanel.TEXT_CREAM;
        if (hand.outcome() >= 0) {
            Outcome outcome = Outcome.byId(hand.outcome());
            if (outcome != null) {
                readingColour = outcome.isWin() ? CasinoPanel.TEXT_WIN
                        : outcome.isPush() ? CasinoPanel.TEXT_PUSH : CasinoPanel.TEXT_LOSE;
            }
        }
        int readingWidth = font.width(reading);
        // The name gets whatever the total leaves, so a long name is cut rather than the number.
        String name = font.plainSubstrByWidth(hand.name().isEmpty() ? "?" : hand.name(),
                width - readingWidth - 3);
        graphics.drawString(font, name, x, topPos + SEAT_LABEL_Y,
                theirTurn ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_MUTED, false);
        graphics.drawString(font, reading, x + width - readingWidth, topPos + SEAT_LABEL_Y,
                readingColour, false);
    }

    /**
     * Between hands: who is at the table, who has said they are ready, and how long the others
     * have to join them.
     *
     * <p>Drawn in the same three columns the hands use, so a chair is in the same place whether it
     * is holding cards or deciding whether to. Names are not sent while the table is idle, so a
     * chair is named by where it is sitting rather than by who is in it.
     */
    private void drawLobby(GuiGraphics graphics) {
        int seats = menu.seatCount();
        int[] places = BlackjackMenu.seatsAround(menu.seatIndex(), seats);
        int readyMask = menu.readout(BlackjackSession.READOUT_READY);
        int presentMask = menu.readout(BlackjackSession.READOUT_PRESENT);
        for (int place = 0; place < 3; place++) {
            int seat = places[place];
            if (seat < 0) continue;
            boolean isReady = (readyMask & (1 << seat)) != 0;
            // An empty chair says nothing: "Betting" under it would be a player who is not there.
            if (!isReady && (presentMask & (1 << seat)) == 0 && seat != menu.seatIndex()) continue;
            boolean mine = seat == menu.seatIndex();
            Component word = Component.translatable(isReady
                    ? "itemcasino.label.seat_ready" : "itemcasino.label.seat_waiting");
            graphics.drawString(font, word, leftPos + placeX(place), topPos + SEAT_LABEL_Y,
                    isReady ? CasinoPanel.TEXT_WIN : (mine ? CasinoPanel.TEXT_CREAM : CasinoPanel.TEXT_MUTED),
                    false);
        }

        int seconds = menu.readout(BlackjackSession.READOUT_COUNTDOWN);
        if (seconds > 0) {
            centred(graphics, Component.translatable("itemcasino.label.deal_in", seconds),
                    CasinoLayout.CENTRE_X, STATUS_Y, CasinoPanel.TEXT_GOLD);
        }
    }

    /** The shoe the cards come out of: the origin of every deal animation, so it is drawn there. */
    private void shoe(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 3, y - 3, x + CardRenderer.CARD_WIDTH + 3,
                y + CardRenderer.CARD_HEIGHT + 3, 0xFF15100B);
        graphics.fill(x - 2, y - 2, x + CardRenderer.CARD_WIDTH + 2,
                y + CardRenderer.CARD_HEIGHT + 2, CasinoPanel.GOLD_DEEP);
        graphics.fill(x - 1, y - 1, x + CardRenderer.CARD_WIDTH + 1,
                y + CardRenderer.CARD_HEIGHT + 1, 0xFF241A12);
        CardRenderer.drawBack(graphics, x, y);
    }

    @Override
    protected Component infoText() {
        return Component.translatable("itemcasino.info.blackjack");
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        // The totals count the cards that have landed, and nothing that is still in the shoe or face
        // down: a total that already knew the dealer's last card would give the hand away.
        boolean dealt = ClientBlackjackState.anyLanded();
        gauge(graphics, CasinoLayout.LEFT_GAUGE_X - 2, DEALER_Y + 4, 46,
                Component.translatable("itemcasino.label.dealer"),
                Component.literal(!dealt ? "--" : ClientBlackjackState.shownDealerTotal()
                        + (ClientBlackjackState.dealerHasHiddenCard() ? "+?" : "")),
                CasinoPanel.TEXT_CREAM);
        // At a shared table this gauge would sit exactly where the left-hand chair's cards go, and
        // its number is already written under those cards along with everyone else's.
        if (menu.seatCount() <= 1) {
            gauge(graphics, CasinoLayout.LEFT_GAUGE_X - 2, PLAYER_Y + 4, 46,
                    Component.translatable("itemcasino.label.you"),
                    Component.literal(!dealt ? "--" : String.valueOf(ClientBlackjackState.shownPlayerTotal())),
                    CasinoPanel.TEXT_CREAM);
        }

        // A badge over the sealed slot, so "doubled" is readable even at a glance.
        if (menu.option() > 1) {
            graphics.drawString(font, Component.translatable("itemcasino.label.doubled"),
                    CasinoLayout.WAGER_X - 4, CasinoLayout.WAGER_Y - 9,
                    CasinoPanel.TEXT_GOLD, false);
        }

        // At a shared table the felt says all of this under the hands themselves: whose turn it is
        // in gold, how long they have left beside their name, and every result in the colour of its
        // total. A line centred across the table would land on top of those labels, and there is no
        // other band of felt left to put it in. The one thing that has nowhere else to go is the
        // lobby, and during the lobby there are no hands and so no labels to collide with.
        if (menu.seatCount() > 1) {
            if (ClientBlackjackState.hands().isEmpty()) drawLobby(graphics);
            return;
        }

        Outcome outcome = ClientBlackjackState.outcome();
        if (ClientBlackjackState.sweeping()) return;   // the hand is leaving the felt, and its result with it
        if (outcome != null && ClientBlackjackState.resultShown()) {
            centred(graphics, Component.translatable(
                            "itemcasino.outcome." + outcome.name().toLowerCase(Locale.ROOT)),
                    CasinoLayout.CENTRE_X, STATUS_Y,
                    outcome.isWin() ? CasinoPanel.TEXT_WIN
                            : outcome.isPush() ? CasinoPanel.TEXT_PUSH : CasinoPanel.TEXT_LOSE);
        } else if (!ClientBlackjackState.allPlaced() || outcome != null) {
            centred(graphics, Component.translatable(outcome != null
                            ? "itemcasino.label.dealer_turn" : "itemcasino.label.dealing"),
                    CasinoLayout.CENTRE_X, STATUS_Y, CasinoPanel.TEXT_MUTED);
        } else if (ClientBlackjackState.phase() == BlackjackPhase.PLAYER_TURN) {
            int seconds = ClientBlackjackState.deadlineTicks() / 20;
            if (seconds <= 10) {
                centred(graphics, Component.translatable("itemcasino.label.acting_timeout", seconds),
                        CasinoLayout.CENTRE_X, STATUS_Y, CasinoPanel.TEXT_LOSE);
            }
        }
    }

    @Override
    protected void renderOverlay(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        super.renderOverlay(graphics, partialTick, mouseX, mouseY);

        // A settled hand tints the felt's edge rather than covering it: the player wants to see the
        // cards that beat them, not a banner sitting on top of them.
        Outcome outcome = ClientBlackjackState.outcome();
        if (outcome == null || !ClientBlackjackState.resultShown() || ClientBlackjackState.sweeping()) return;
        int colour = outcome.isWin() ? 0x807BD88F
                : outcome.isPush() ? 0x80E3CE7A : 0x80E07A6B;
        CasinoPanel.frame(graphics, leftPos + CasinoLayout.FELT_X - 1,
                topPos + CasinoLayout.FELT_Y - 1,
                CasinoLayout.FELT_W + 2, CasinoLayout.FELT_H + 2, colour);
    }

    @Override
    public void onClose() {
        ClientBlackjackState.reset();
        super.onClose();
    }
}
