package com.itemcasino.client.screen;

import com.itemcasino.client.ClientSessionState;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.WheelRenderer;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.CoinFlipMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The duel.
 *
 * <p>Two stakes face each other across the action row, each in its owner's colour, and the coin
 * between them wears both: red is the near face and belongs to the left chair, blue the far one and
 * the right. So the moment it stops, both players already know who won without reading anything.
 */
public class CoinFlipScreen extends AbstractCasinoScreen<CoinFlipMenu> {

    private static final int COIN_CX = CasinoLayout.CENTRE_X;
    private static final int COIN_CY = 56;
    private static final int COIN_R = 28;

    /** The two faces of the coin, one per chair. */
    private static final int SEAT_A_COLOUR = 0xFFC0392B;
    private static final int SEAT_B_COLOUR = 0xFF2E6DA4;

    public CoinFlipScreen(CoinFlipMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected int primaryButtonWidth() { return CasinoLayout.DUEL_BUTTON_W; }

    @Override
    protected String wagerLabelKey() { return "itemcasino.button.ready"; }

    @Override
    protected boolean canWager() {
        return menu.seatIndex() >= 0 && menu.option() == 1;
    }

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        boolean settled = ClientSessionState.phase() == ClientSessionState.Phase.RESULT;
        boolean mine = settled && ClientSessionState.winnerSeat() == menu.seatIndex();

        WheelRenderer.drawCoin(graphics, leftPos + COIN_CX, topPos + COIN_CY, COIN_R,
                ClientSessionState.currentAngle(partialTick),
                SEAT_A_COLOUR, SEAT_B_COLOUR, 0xFF20180F, settled, mine);

        // A ribbon under each stake, so a glance at the table says which chair is which.
        swatch(graphics, CasinoLayout.WAGER_X, SEAT_A_COLOUR, menu.seatIndex() == 0);
        swatch(graphics, CasinoLayout.WAGER_B_X, SEAT_B_COLOUR, menu.seatIndex() == 1);
    }

    private void swatch(GuiGraphics graphics, int x, int colour, boolean yours) {
        int y = topPos + CasinoLayout.WAGER_Y - 6;
        graphics.fill(leftPos + x - 2, y, leftPos + x + 18, y + 3, colour);
        if (yours) {
            CasinoPanel.frame(graphics, leftPos + x - 3, y - 1, 22, 5, CasinoPanel.GOLD);
        }
    }

    @Override
    protected Component infoText() {
        return Component.translatable("itemcasino.tooltip.coin_flip_edge");
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        int seat = menu.seatIndex();
        boolean matched = menu.option() == 1;
        int rightX = CasinoLayout.RIGHT_GAUGE_X - CasinoLayout.GAUGE_W;

        gauge(graphics, CasinoLayout.LEFT_GAUGE_X, 28, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.seat_red"),
                Component.translatable(seat == 0 ? "itemcasino.label.you" : "itemcasino.label.them"),
                SEAT_A_COLOUR);
        gauge(graphics, rightX, 28, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.seat_blue"),
                Component.translatable(seat == 1 ? "itemcasino.label.you" : "itemcasino.label.them"),
                SEAT_B_COLOUR);

        // The two numbers that make the duel honest, priced by the server and shown to both
        // players: nobody has to take the other's word for what a stack is worth.
        gauge(graphics, CasinoLayout.LEFT_GAUGE_X, 58, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.stake"),
                stake(0), stakeColour(matched, 0));
        gauge(graphics, rightX, 58, CasinoLayout.GAUGE_W,
                Component.translatable("itemcasino.label.stake"),
                stake(1), stakeColour(matched, 1));

        centred(graphics, statusLine(matched, seat), COIN_CX, 96, statusColour(matched, seat));
    }

    /** A stake's worth, or a dash while the chair is empty. */
    private Component stake(int seat) {
        int milli = menu.stakeMilli(seat);
        return milli < 0 ? Component.literal("--") : Component.literal(Fixed.format(milli * 1000L));
    }

    /**
     * Amber on the heavier side when the two do not match, so the player who is being short-changed
     * can see which way at a glance rather than comparing two numbers.
     */
    private int stakeColour(boolean matched, int seat) {
        int mine = menu.stakeMilli(seat);
        int theirs = menu.stakeMilli(seat == 0 ? 1 : 0);
        if (mine < 0 || theirs < 0) return CasinoPanel.TEXT_MUTED;
        if (matched) return CasinoPanel.TEXT_WIN;
        return mine > theirs ? CasinoPanel.TEXT_PUSH : CasinoPanel.TEXT_LOSE;
    }

    private Component statusLine(boolean matched, int seat) {
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT) {
            return Component.translatable(ClientSessionState.winnerSeat() == seat
                    ? "itemcasino.label.duel_won" : "itemcasino.label.duel_lost");
        }
        if (seat < 0) return Component.translatable("itemcasino.label.duel_watching");
        if (menu.gameState() == GameState.ARMED) {
            return Component.translatable("itemcasino.label.duel_ready");
        }
        return Component.translatable(matched
                ? "itemcasino.label.duel_ready" : "itemcasino.label.duel_unmatched");
    }

    private int statusColour(boolean matched, int seat) {
        if (ClientSessionState.phase() == ClientSessionState.Phase.RESULT) {
            return ClientSessionState.winnerSeat() == seat
                    ? CasinoPanel.TEXT_WIN : CasinoPanel.TEXT_LOSE;
        }
        return matched ? CasinoPanel.TEXT_CREAM : CasinoPanel.TEXT_MUTED;
    }
}
