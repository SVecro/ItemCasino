package com.itemcasino.client.screen;

import com.itemcasino.client.ClientBlackjackState;
import com.itemcasino.client.CasinoSounds;
import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.render.CardRenderer;
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

    private final Map<BlackjackAction, Button> actionButtons = new EnumMap<>(BlackjackAction.class);

    public BlackjackScreen(BlackjackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected String wagerLabelKey() {
        return "itemcasino.button.deal";
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
        int shoeX = leftPos + CasinoLayout.SHOE_X;
        int shoeY = topPos + CasinoLayout.SHOE_Y;
        shoe(graphics, shoeX, shoeY);

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

        int playerSpacing = CardRenderer.spacingFor(player.size(), HAND_SPACE);
        for (int i = 0; i < player.size(); i++) {
            float age = ClientBlackjackState.placementAge(false, i, partialTick);
            if (age < 0F) continue;
            CardRenderer.drawDealt(graphics, player.get(i),
                    leftPos + HAND_X + i * playerSpacing, topPos + PLAYER_Y,
                    shoeX, shoeY, age, ClientBlackjackState.SLIDE_TICKS);
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
        return Component.translatable("itemcasino.tooltip.blackjack_edge");
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
        gauge(graphics, CasinoLayout.LEFT_GAUGE_X - 2, PLAYER_Y + 4, 46,
                Component.translatable("itemcasino.label.you"),
                Component.literal(!dealt ? "--" : String.valueOf(ClientBlackjackState.shownPlayerTotal())),
                CasinoPanel.TEXT_CREAM);

        // A badge over the sealed slot, so "doubled" is readable even at a glance.
        if (menu.option() > 1) {
            graphics.drawString(font, Component.translatable("itemcasino.label.doubled"),
                    CasinoLayout.WAGER_X - 4, CasinoLayout.WAGER_Y - 9,
                    CasinoPanel.TEXT_GOLD, false);
        }

        Outcome outcome = ClientBlackjackState.outcome();
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
        if (outcome == null || !ClientBlackjackState.resultShown()) return;
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
