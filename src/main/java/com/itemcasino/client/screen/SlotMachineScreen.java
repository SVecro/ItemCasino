package com.itemcasino.client.screen;

import com.itemcasino.client.ClientSlotState;
import com.itemcasino.client.render.CasinoPanel;
import com.itemcasino.client.render.InfoBadge;
import com.itemcasino.client.render.ReelRenderer;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.slots.SlotMachine;
import com.itemcasino.core.game.slots.SlotOutcome;
import com.itemcasino.core.game.slots.SlotSymbol;
import com.itemcasino.menu.CasinoLayout;
import com.itemcasino.menu.SlotMachineMenu;
import com.itemcasino.client.CasinoSounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Three reels, a paytable either side, and one button.
 *
 * <p>The paytable is on screen rather than in a tooltip because a machine that will not tell you
 * what it pays is a machine you should not put items into. The two columns are the six three-of-a-
 * kind prizes; the line underneath covers the pairs, which are where most of the return actually
 * comes from.
 */
public class SlotMachineScreen extends AbstractCasinoScreen<SlotMachineMenu> {

    private static final int REEL_GAP = 6;
    private static final int REEL_SPAN =
            SlotMachine.REELS * ReelRenderer.WIDTH + (SlotMachine.REELS - 1) * REEL_GAP;
    private static final int REEL_X = (CasinoLayout.WIDTH - REEL_SPAN) / 2;
    private static final int REEL_Y = 28;

    private static final int PAY_LEFT_X = 10;
    private static final int PAY_RIGHT_X = CasinoLayout.WIDTH - 10 - 44;
    private static final int PAY_Y = 28;
    private static final int PAY_ROW = 16;
    /** Under the plates, which end at PAY_Y + 3 rows + padding. Nothing may sit inside that band. */
    private static final int HINT_Y = PAY_Y + 3 * PAY_ROW + 8;   // 84
    private static final int STATUS_Y = 96;

    /** Which face each paytable column lists, richest first. */
    private static final SlotSymbol[] LEFT_COLUMN =
            { SlotSymbol.STAR, SlotSymbol.DIAMOND, SlotSymbol.GOLD };
    private static final SlotSymbol[] RIGHT_COLUMN =
            { SlotSymbol.IRON, SlotSymbol.COPPER, SlotSymbol.COAL };

    private int stoppedReels;
    private final int[] lastDetent = new int[SlotMachine.REELS];

    public SlotMachineScreen(SlotMachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ClientSlotState.tick();

        // Someone who opened the machine mid-spin never saw the result packet; the menu's data
        // channel still knows where the reels are going.
        if (!ClientSlotState.spinning() && menu.gameState() == GameState.ROLLING) {
            ClientSlotState.adopt(menu.reelState());
        }

        int settled = 0;
        boolean ticked = false;
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            boolean stopped = ClientSlotState.stopped(reel, 0F);
            if (stopped) { settled++; continue; }

            // A ting for every symbol that goes past the payline. Early in the spin the reel covers
            // several a tick and only one is heard; by the end they are seconds apart. At most one
            // reel is allowed to speak per tick, or three of them together is a buzz, not a slot
            // machine.
            int detent = (int) Math.floor(ClientSlotState.position(reel, 0F));
            if (!ticked && lastDetent[reel] != Integer.MIN_VALUE && detent != lastDetent[reel]) {
                CasinoSounds.reelTick(reel);
                ticked = true;
            }
            lastDetent[reel] = detent;
        }
        if (settled > stoppedReels && ClientSlotState.spinning()) {
            // The win or loss is sounded by the reveal, with the banner, once the server has settled.
            CasinoSounds.reelStop(settled - 1);
        }
        if (!ClientSlotState.spinning()) java.util.Arrays.fill(lastDetent, Integer.MIN_VALUE);
        stoppedReels = settled;
    }

    @Override
    protected void refreshWidgets() {
        super.refreshWidgets();
        // Nothing is collectable until the last reel has settled: the payout is already waiting on
        // the server, this only stops the button from cutting the spin short.
        if (actionButton != null && !ClientSlotState.allStopped()) actionButton.active = false;
    }

    @Override
    protected void renderTable(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            int x = leftPos + REEL_X + reel * (ReelRenderer.WIDTH + REEL_GAP);
            int y = topPos + REEL_Y;
            ReelRenderer.housing(graphics, x, y);
            ReelRenderer.band(graphics, x, y, ClientSlotState.position(reel, partialTick));
            ReelRenderer.payline(graphics, x, y, ClientSlotState.stopped(reel, partialTick));
        }
    }

    @Override
    protected void renderReadouts(GuiGraphics graphics) {
        paytable(graphics, PAY_LEFT_X, LEFT_COLUMN);
        paytable(graphics, PAY_RIGHT_X, RIGHT_COLUMN);

        // Under the plates, so it has the width of the felt; squeezed if a translation is longer.
        centredFit(graphics, status(), CasinoLayout.CENTRE_X, STATUS_Y, CasinoLayout.FELT_W - 8,
                statusColour());
    }

    /** The rules the paytable cannot fit: what pairs pay, and the machine's return. */
    @Override
    protected Component infoText() {
        long[] exact = SlotMachine.exactReturn();
        long returnPpm = exact[1] == 0 ? 0 : exact[0] * 1_000_000L / exact[1];
        return Component.translatable("itemcasino.info.slots",
                name(SlotSymbol.STAR), SlotSymbol.STAR.pairMultiplier(),
                name(SlotSymbol.DIAMOND), SlotSymbol.DIAMOND.pairMultiplier(),
                name(SlotSymbol.GOLD), SlotSymbol.GOLD.pairMultiplier(),
                name(SlotSymbol.IRON), name(SlotSymbol.COPPER), name(SlotSymbol.COAL),
                InfoBadge.percent(returnPpm));
    }

    private static Component name(SlotSymbol symbol) {
        return ReelRenderer.stack(symbol).getHoverName();
    }

    /** One column of "three of these pay this much", icon then multiplier. */
    private void paytable(GuiGraphics graphics, int x, SlotSymbol[] column) {
        CasinoPanel.plate(graphics, x, PAY_Y - 4, 44, 3 * PAY_ROW + 4);
        for (int row = 0; row < column.length; row++) {
            SlotSymbol symbol = column[row];
            int y = PAY_Y + row * PAY_ROW;
            graphics.renderItem(ReelRenderer.stack(symbol), x + 3, y);
            String label = "x" + symbol.tripleMultiplier();
            graphics.drawString(font, label, x + 40 - font.width(label), y + 4,
                    symbol.tripleMultiplier() >= 30 ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_CREAM,
                    false);
        }
    }

    /** The machine's ceiling on an item stake, as the server reports it. */
    private int maxItemStake() {
        int most = menu.readout(com.itemcasino.session.SlotMachineSession.READOUT_MAX_ITEMS);
        return most > 0 ? most : Integer.MAX_VALUE;
    }

    @Override
    protected ItemStack stakeTakenFrom(ItemStack slot) {
        if (com.itemcasino.chips.ChipCards.isCard(slot) || slot.getCount() <= maxItemStake()) return slot;
        return slot.copyWithCount(maxItemStake());
    }

    private Component status() {

        if (!ClientSlotState.allStopped()) {
            return Component.translatable("itemcasino.label.slots_spinning");
        }
        if (!ClientSlotState.spinning()) {
            if (menu.gameState() != GameState.ARMED) return Component.translatable("itemcasino.label.slots_idle");
            ItemStack slot = menu.ownWagerStack();
            int most = maxItemStake();
            return !com.itemcasino.chips.ChipCards.isCard(slot) && slot.getCount() > most
                    ? Component.translatable("itemcasino.label.slots_takes_part", most)
                    : Component.translatable("itemcasino.label.slots_ready");
        }
        SlotOutcome outcome = ClientSlotState.outcome();
        if (!outcome.paysAnything()) {
            // Two alike that pay nothing are still two alike: calling them "three different" when the
            // player can see a pair on the payline reads as the machine being wrong.
            SlotSymbol pair = deadPair(outcome);
            return pair == null ? Component.translatable("itemcasino.label.slots_nothing")
                    : Component.translatable("itemcasino.label.slots_dead_pair", name(pair));
        }
        Component name = ReelRenderer.stack(outcome.paying()).getHoverName();
        return Component.translatable(outcome.kind() == SlotOutcome.Kind.TRIPLE
                        ? "itemcasino.label.slots_triple" : "itemcasino.label.slots_pair",
                name, outcome.multiplier());   // "Iron Ingot x3 - pays x4"; no plural to get wrong
    }

    /** The symbol of a pair on the payline that pays nothing, or null when the three faces differ. */
    private static SlotSymbol deadPair(SlotOutcome outcome) {
        if (outcome.left() == outcome.middle() || outcome.left() == outcome.right()) return outcome.leftSymbol();
        if (outcome.middle() == outcome.right()) return outcome.middleSymbol();
        return null;
    }

    private int statusColour() {
        if (!ClientSlotState.allStopped() || !ClientSlotState.spinning()) {
            return CasinoPanel.TEXT_MUTED;
        }
        SlotOutcome outcome = ClientSlotState.outcome();
        if (!outcome.paysAnything()) return CasinoPanel.TEXT_LOSE;
        return outcome.multiplier() >= 30 ? CasinoPanel.TEXT_GOLD : CasinoPanel.TEXT_WIN;
    }

    @Override
    public void onClose() {
        ClientSlotState.reset();
        super.onClose();
    }
}
