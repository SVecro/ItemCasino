package com.itemcasino.session;

import com.itemcasino.chips.ChipCards;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.SeededRoller;
import com.itemcasino.core.game.blackjack.BlackjackAction;
import com.itemcasino.core.game.blackjack.BlackjackPhase;
import com.itemcasino.core.game.blackjack.BlackjackTable;
import com.itemcasino.core.game.blackjack.Card;
import com.itemcasino.core.game.blackjack.Outcome;
import com.itemcasino.core.game.blackjack.Rules;
import com.itemcasino.core.game.blackjack.Settlement;
import com.itemcasino.network.s2c.S2CBlackjackSettled;
import com.itemcasino.network.s2c.S2CBlackjackState;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.mojang.serialization.Codec;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Item blackjack. The wagered stack is the collateral; the multiplier comes from {@link Settlement}.
 *
 * <h2>How a hand survives a restart</h2>
 * Every other game decides its outcome at lock time, so a crash is repaired by materialising what
 * was already owed. A hand in progress has no decided outcome, so it is persisted as the three
 * things that determine it completely: the seed of its shoe, the rules it was dealt under, and the
 * actions taken so far. On load the hand is rebuilt card for card — hole card included — and stood,
 * exactly as the action timer would have stood it.
 *
 * <p>It used to be voided instead, with the stake refunded in full. That turned "Save and Quit" on
 * a bad hand into a free option: voiding every hard 12-16 against a dealer 7-ace moved the player
 * from -0.6 % to +8.6 % per hand. A rebuilt shoe cannot be reshuffled by pulling the plug, and a
 * forced stand is never better for the player than what they could have chosen themselves.
 */
public class BlackjackSession extends CasinoSession {

    @Nullable private transient BlackjackTable table;
    private ItemStack doubleEscrow = ItemStack.EMPTY;
    /** A chip hand that doubled: the second stake is chips on the same card, so nothing moves. */
    private boolean doubledChips;

    /** The shoe's seed; 0 when no hand is in flight. Server-side only, never sent to a client. */
    private long handSeed;
    /** Ordinals of the actions applied to the current hand, in order. */
    private final List<Integer> handActions = new ArrayList<>();
    /** The rules the hand was dealt under, so a config change cannot alter a replay. */
    @Nullable private Rules handRules;
    /** Cards the viewers have been sent so far this hand, player's and dealer's (hole included). */
    private int shownPlayerCards;
    private int shownDealerCards;
    /**
     * The hand is decided and its end is being shown. The payout waits for the viewer to have seen
     * the cards that decide it, exactly as a wheel's payout waits for the wheel. Not persisted: a
     * restore rebuilds and settles the hand outright.
     */
    private boolean revealing;
    /** The hand rebuilt by the last restore, kept so a test can compare it with the original. */
    @Nullable private transient BlackjackTable restoredHand;

    public BlackjackSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_BLACKJACK; }

    @Nullable public BlackjackTable table() { return table; }

    @Nullable public BlackjackTable restoredHand() { return restoredHand; }

    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        setState(wagerStack().isEmpty() ? GameState.IDLE : GameState.ARMED);
    }

    /**
     * How many units of the original wager are riding on this hand: one normally, two after a
     * double. The screen multiplies the sealed slot's ghost by it, because a doubled bet that still
     * shows the original stack is a lie about how much is at stake -- and it is at stake, the
     * collateral came out of the player's inventory the moment they pressed the button.
     */
    @Override
    public int option() {
        return doubleEscrow.isEmpty() && !doubledChips ? 1 : 2;
    }

    /** A doubled chip hand has twice the chips at stake, on the one card. */
    @Override
    protected long committedStakeCents() {
        return doubledChips ? stakeCents * 2 : stakeCents;
    }

    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
        if (!isSeated(player)) return false;
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!checkStake(player, snapshot)) return false;

        this.frozenSnapshot = snapshot;
        this.sessionId++;
        doubledChips = false;
        escrowStake();
        if (!setState(GameState.LOCKED)) return false;
        beginCommit(player, 0L);
        touch();
        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), 0));
        return deal(player);
    }

    public boolean deal(ServerPlayer player) {
        if (state != GameState.LOCKED) return false;
        if (!isSeated(player)) return false;

        RandomSource random = host.random();
        long seed = random.nextLong();
        this.handSeed = seed == 0L ? 1L : seed;
        this.handRules = rules();
        this.handActions.clear();
        this.table = new BlackjackTable(handRules, new SeededRoller(handSeed));
        this.table.deal();
        this.shownPlayerCards = 0;
        this.shownDealerCards = 0;
        this.revealing = false;
        host.markDirty();
        if (!setState(GameState.ROLLING)) return false;
        resetActionDeadline();

        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.AUDIT.info("[wager] {} blackjack session={} wager={}x{} chips={}c",
                    player.getName().getString(), sessionId, escrow.getCount(),
                    BuiltInRegistries.ITEM.getKey(escrow.getItem()), stakeCents);
        }
        if (table.phase() == BlackjackPhase.SETTLED) beginReveal();   // a natural ends it at once
        else broadcastState();
        return true;
    }

    /**
     * Legality is re-derived here from the server's own hand; the mask the client was given is a
     * courtesy for greying out buttons, never an authorisation.
     */
    public boolean act(ServerPlayer player, long claimedSession, @Nullable BlackjackAction action) {
        if (state != GameState.ROLLING || table == null) return false;
        if (claimedSession != sessionId || action == null) return false;
        if (!isSeated(player)) return false;
        if (!action.isIn(legalMaskFor(player))) return false;

        if (action == BlackjackAction.DOUBLE && !takeDoubleCollateral(player)) return false;
        if (!table.apply(action)) {
            // Unreachable in practice, but holding a second stake for a hand that never doubled
            // would quietly eat it.
            if (action == BlackjackAction.DOUBLE) refundDoubleCollateral(player);
            return false;
        }
        handActions.add(action.ordinal());
        host.markDirty();
        resetActionDeadline();
        touch();
        if (table.phase() == BlackjackPhase.SETTLED) beginReveal();
        else broadcastState();
        return true;
    }

    /** The table's mask, minus DOUBLE when this player cannot actually cover a second stake. */
    public int legalMaskFor(@Nullable Player player) {
        if (table == null) return 0;
        int mask = table.legalMask();
        if (player != null && BlackjackAction.DOUBLE.isIn(mask) && !canAffordDouble(player)) {
            mask &= ~BlackjackAction.DOUBLE.bit();
        }
        return mask;
    }

    /**
     * The hand is over on the server. Every viewer is sent the whole of it — the hole card, the
     * dealer's draws, the outcome — and the payout is held back for as long as a client takes to lay
     * those cards down, or until the client says it has.
     */
    private void beginReveal() {
        if (table == null || table.phase() != BlackjackPhase.SETTLED) {
            settle();
            return;
        }
        revealing = true;
        int ticks = com.itemcasino.core.game.blackjack.DealClock.revealTicks(
                shownPlayerCards, shownDealerCards, table.player().cards().size(), table.dealer().cards().size());
        deadlineTick = host.hostLevel().getGameTime() + ticks + 40L;
        armAcknowledgement(ticks);
        host.markDirty();

        host.broadcast(settledFactory());
    }

    /** The settled hand, for every viewer or for one who arrives during the reveal. */
    private java.util.function.IntFunction<net.minecraft.network.protocol.common.custom.CustomPacketPayload> settledFactory() {
        BlackjackTable t = table;
        Settlement settlement = t.settlement();
        List<Card> dealerFinal = List.copyOf(t.dealer().cards());
        List<Card> playerFinal = List.copyOf(t.player().cards());
        byte outcome = settlement == null ? (byte) Outcome.PUSH.ordinal() : (byte) settlement.outcome().ordinal();
        int units = settlement == null ? 1 : settlement.betUnits();
        int playerTotal = settlement == null ? t.player().total() : settlement.playerTotal();
        int dealerTotal = settlement == null ? t.dealer().total() : settlement.dealerTotal();
        long id = sessionId;
        return container -> new S2CBlackjackSettled(container, id, outcome, units, dealerFinal, playerFinal,
                playerTotal, dealerTotal);
    }

    @Override
    public boolean commitWager(ServerPlayer player) { return placeWager(player); }

    @Override
    public boolean acknowledge(long claimedSession) { return finishReveal(claimedSession); }

    /** A doubled item bet stakes the escrow and the collateral, both of the same kind. */
    @Override
    protected long stakedItemCount() {
        return (long) escrow.getCount() + doubleEscrow.getCount();
    }

    /**
     * Someone opened the table while a hand is in play: typically its own player, back after closing
     * the screen. Their client dropped the hand when the screen closed, so it is sent again, with
     * the session id their buttons must quote; during the reveal, the whole settled hand.
     */
    @Override
    public void onViewerOpened(ServerPlayer player) {
        if (state != GameState.ROLLING || table == null) return;
        sendTo(player, id -> new S2CSessionStarted(id, sessionId, gameType(), 0));
        if (revealing && table.phase() == BlackjackPhase.SETTLED) {
            sendTo(player, settledFactory());
        } else {
            sendTo(player, stateFactory());
        }

    }

    /** The client has laid down the last card of a settled hand. */
    public boolean finishReveal(long claimedSession) {
        if (state != GameState.ROLLING || !revealing) return false;
        if (claimedSession != sessionId) return false;
        settle();
        return true;
    }

    /**
     * A player who lets the clock run out is stood, and then shown the dealer's hand like anyone
     * else; only a reveal that nobody acknowledges is settled outright.
     */
    @Override
    protected void onDeadline() {
        if (state == GameState.ROLLING && !revealing && table != null
                && table.phase() == BlackjackPhase.PLAYER_TURN) {
            table.forceStand();
            touch();
            beginReveal();
            return;
        }
        forceSettle();
    }

    @Override
    public void forceSettle() {
        if (state != GameState.ROLLING) return;
        if (table != null && table.phase() == BlackjackPhase.PLAYER_TURN) {
            // Standing is never worse than forfeiting, so an away player is not punished for the
            // server's impatience.
            table.forceStand();
        }
        settle();
    }

    private void settle() {
        if (!setState(GameState.SETTLING)) return;
        revealing = false;
        Settlement settlement = table == null ? null : table.settlement();
        decidedWin = settlement != null && settlement.outcome().isWin();
        materialiseDecidedOutcome();
        recordOutcome(stakedValue() + valueOf(doubleEscrow), returnedValue());
        // Both halves of a doubled bet are at stake, so both count toward what was lost.
        if (stakeIsChips()) bankLoss();
        else bankLoss(escrow.getCount() + doubleEscrow.getCount());
        escrow = ItemStack.EMPTY;
        doubleEscrow = ItemStack.EMPTY;
        doubledChips = false;
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        returnCardsToSlots();
        touch();

        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.AUDIT.info("[settle] blackjack session={} outcome={} units={} payout={}",
                    sessionId, settlement == null ? "VOID" : settlement.outcome(),
                    settlement == null ? 0 : settlement.betUnits(), payout);
        }
        table = null;
        clearHandRecord();
        broadcastPayout();
    }

    private void clearHandRecord() {
        handSeed = 0L;
        handActions.clear();
        handRules = null;
    }

    /** Rebuilds the hand in flight from its seed and actions, or null when there is none to rebuild. */
    @Nullable
    private BlackjackTable rebuildHand() {
        if (handSeed == 0L || handRules == null) return null;
        BlackjackTable rebuilt = new BlackjackTable(handRules, new SeededRoller(handSeed));
        rebuilt.deal();
        for (int ordinal : handActions) {
            BlackjackAction action = BlackjackAction.byId(ordinal);
            if (action == null || !rebuilt.apply(action)) {
                ItemCasino.LOGGER.error("Blackjack session {}: action {} did not replay; standing from here",
                        sessionId, ordinal);
                break;
            }
        }
        return rebuilt;
    }

    /**
     * After a reload: rebuild the hand, stand it if it was still the player's turn, and settle what
     * that produces. Only a session saved before hands were recorded falls back to a refund.
     */
    @Override
    protected void repairAfterLoad() {
        if (state.holdsEscrow() && table == null) {
            BlackjackTable rebuilt = rebuildHand();
            if (rebuilt != null) {
                if (rebuilt.phase() == BlackjackPhase.PLAYER_TURN) rebuilt.forceStand();
                this.table = rebuilt;
                this.restoredHand = rebuilt;
                ItemCasino.LOGGER.info("Blackjack session {} restored from its seed and stood ({})",
                        sessionId, rebuilt.settlement() == null ? "unsettled" : rebuilt.settlement().outcome());
            }
        }
        super.repairAfterLoad();
        if (!state.holdsEscrow()) {
            // The base repair empties the escrow but knows nothing of the double-down collateral,
            // which the payout above has already accounted for (paid in the multiplier, or
            // refunded). Left set, it was handed back a second time if the table was then broken.
            doubleEscrow = ItemStack.EMPTY;
            doubledChips = false;
        }
        table = null;
        clearHandRecord();
    }

    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        if (escrow.isEmpty()) return;
        Settlement settlement = table == null ? null : table.settlement();
        if (stakeIsChips()) {
            // payNumerator already folds in a double, measured against the original stake. With
            // nothing to rebuild the hand is void: the whole stake, doubled or not, comes back.
            payChips(settlement == null ? committedStakeCents()
                    : Chips.payout(stakeCents, settlement.payNumerator(), settlement.payDenominator()));
            return;
        }
        if (settlement == null) {
            // Only reachable for a session saved before hands were recorded: nothing to rebuild.
            List<ItemStack> refund = new ArrayList<>(4);
            PayoutResolver.addSplit(refund, escrow, escrow.getCount());
            if (!doubleEscrow.isEmpty()) {
                PayoutResolver.addSplit(refund, doubleEscrow, doubleEscrow.getCount());
            }
            setPayout(refund);
            return;
        }
        // payNumerator already folds in the doubled bet, so this is measured against the ORIGINAL
        // stack: a won double is 4x, a natural 5/2, a surrender 1/2.
        setPayout(PayoutResolver.multiply(escrow, settlement.payNumerator(),
                settlement.payDenominator(), snapshot()));
    }

    public void broadcastState() {
        if (table == null) return;
        shownPlayerCards = table.player().cards().size();
        shownDealerCards = table.dealerVisible().cards().size() + (table.holeHidden() ? 1 : 0);
        host.broadcast(stateFactory());
    }

    /** The hand as it stands, as every viewer is shown it. */
    private java.util.function.IntFunction<net.minecraft.network.protocol.common.custom.CustomPacketPayload> stateFactory() {
        BlackjackTable t = table;
        int remaining = (int) Math.max(0, deadlineTick - host.hostLevel().getGameTime());
        List<Card> playerHand = List.copyOf(t.player().cards());
        List<Card> dealerVisible = List.copyOf(t.dealerVisible().cards());
        byte phase = (byte) t.phase().ordinal();
        int playerTotal = t.player().total();
        int dealerTotal = t.holeHidden() ? t.dealerVisible().total() : t.dealer().total();
        boolean hidden = t.holeHidden();
        // The mask is the seated player's: only they can act, and only they can afford a double.
        int mask = legalMaskFor(host.seatedPlayer());
        long id = sessionId;
        return container -> new S2CBlackjackState(container, id, phase, playerHand, dealerVisible,
                hidden, mask, playerTotal, dealerTotal, remaining);
    }

    // ------------------------------------------------------------------ double-down collateral

    private boolean canAffordDouble(Player player) {
        if (stakeIsChips()) return !doubledChips && ChipCards.balance(escrow) >= stakeCents * 2;
        return countMatching(player, escrow) >= escrow.getCount();
    }

    private boolean takeDoubleCollateral(ServerPlayer player) {
        if (stakeIsChips()) {
            if (!canAffordDouble(player)) return false;
            doubledChips = true;
            host.markDirty();
            return true;
        }
        int needed = escrow.getCount();
        if (countMatching(player, escrow) < needed) return false;

        Inventory inventory = player.getInventory();
        int remaining = needed;
        // The 36 carried slots only. getContainerSize() also counts the armour, the offhand, the body
        // armour and the saddle, so doubling a bet on a chestplate used to take the one being worn.
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!ItemStack.isSameItemSameComponents(stack, escrow)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            remaining -= take;
        }
        if (remaining > 0) {
            ItemCasino.LOGGER.error("Double-down collateral came up {} short; refunding", remaining);
            ItemStack refund = escrow.copy();
            refund.setCount(needed - remaining);
            giveTo(player, List.of(refund));
            return false;
        }
        ItemStack taken = escrow.copy();
        taken.setCount(needed);
        this.doubleEscrow = taken;
        host.markDirty();
        return true;
    }

    private void refundDoubleCollateral(ServerPlayer player) {
        if (doubledChips) {
            doubledChips = false;
            host.markDirty();
            return;
        }
        if (doubleEscrow.isEmpty()) return;
        ItemStack back = doubleEscrow.copy();
        doubleEscrow = ItemStack.EMPTY;
        giveTo(player, List.of(back));
        host.markDirty();
    }

    private static int countMatching(Player player, ItemStack prototype) {
        if (prototype.isEmpty()) return 0;
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (ItemStack.isSameItemSameComponents(inventory.getItem(slot), prototype)) {
                total += inventory.getItem(slot).getCount();
            }
        }
        return total;
    }

    private Rules rules() {
        return new Rules(
                CasinoConfig.SERVER.blackjackDecks.get(),
                CasinoConfig.SERVER.dealerHitsSoft17.get(),
                CasinoConfig.SERVER.allowDouble.get(),
                CasinoConfig.SERVER.allowSurrender.get(),
                CasinoConfig.SERVER.dealerPeek.get());
    }

    private void resetActionDeadline() {
        deadlineTick = host.hostLevel().getGameTime()
                + CasinoConfig.SERVER.playerActionSeconds.get() * 20L;
    }

    @Override
    public void liquidate(@Nullable ServerPlayer to) {
        if (!doubleEscrow.isEmpty()) {
            ItemStack back = doubleEscrow.copy();
            doubleEscrow = ItemStack.EMPTY;
            giveTo(to, List.of(back));
        }

        doubledChips = false;
        table = null;
        clearHandRecord();
        super.liquidate(to);
    }

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.store("double_escrow", ItemStack.OPTIONAL_CODEC, doubleEscrow);
        out.putBoolean("doubled_chips", doubledChips);
        if (handSeed != 0L && handRules != null) {
            out.putLong("hand_seed", handSeed);
            out.store("hand_actions", Codec.INT.listOf(), List.copyOf(handActions));
            out.store("hand_rules", Codec.INT.listOf(), List.of(handRules.decks(),
                    handRules.dealerHitsSoft17() ? 1 : 0, handRules.allowDouble() ? 1 : 0,
                    handRules.allowSurrender() ? 1 : 0, handRules.dealerPeek() ? 1 : 0));
        }
    }

    @Override
    public void load(ValueInput in) {
        doubleEscrow = in.read("double_escrow", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        doubledChips = in.getBooleanOr("doubled_chips", false);
        handSeed = in.getLongOr("hand_seed", 0L);
        handActions.clear();
        in.read("hand_actions", Codec.INT.listOf()).ifPresent(handActions::addAll);
        handRules = in.read("hand_rules", Codec.INT.listOf())
                .filter(r -> r.size() == 5)
                .map(r -> {
                    try {
                        return new Rules(r.get(0), r.get(1) != 0, r.get(2) != 0, r.get(3) != 0, r.get(4) != 0);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .orElse(null);
        super.load(in);   // runs repairAfterLoad, which needs the double escrow above
    }
}
