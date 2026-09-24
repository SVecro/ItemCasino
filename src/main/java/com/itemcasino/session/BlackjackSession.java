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
import com.itemcasino.core.value.Fixed;
import com.itemcasino.network.s2c.S2CBlackjackSettled;
import com.itemcasino.network.s2c.SeatHand;
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
    /** The second stake a chair put up to double, one per chair. */
    private final ItemStack[] doubleEscrow;
    /** A chip hand that doubled: the second stake is chips on the same card, so nothing moves. */
    private final boolean[] doubledChips;
    /** Which chairs were dealt in this hand, as a bitmask, so a restore deals to the same ones. */
    private int handPlaying;
    /** Who has said they are ready to play the next hand. Only a table with more than one chair. */
    private final boolean[] ready;
    /** When the countdown that started at the first "ready" runs out; 0 when none is running. */
    private long readyDeadlineTick;

    /** The shoe's seed; 0 when no hand is in flight. Server-side only, never sent to a client. */
    private long handSeed;
    /** Ordinals of the actions applied to the current hand, in order. */
    private final List<Integer> handActions = new ArrayList<>();
    /** The rules the hand was dealt under, so a config change cannot alter a replay. */
    @Nullable private Rules handRules;
    /** Cards the viewers have been sent so far this hand, player's and dealer's (hole included). */
    private int shownCards;
    private int shownDealerCards;
    /**
     * The hand is decided and its end is being shown. The payout waits for the viewer to have seen
     * the cards that decide it, exactly as a wheel's payout waits for the wheel. Not persisted: a
     * restore rebuilds and settles the hand outright.
     */
    private boolean revealing;
    /** The hand rebuilt by the last restore, kept so a test can compare it with the original. */
    @Nullable private transient BlackjackTable restoredHand;
    /**
     * Each chair's result, captured at settle so the banner can be addressed to the chair it
     * belongs to. The hand itself is thrown away at settle, and the banner goes out after.
     */
    @Nullable private transient byte[] lastTier;
    @Nullable private transient long[] lastCents;
    @Nullable private transient List<List<ItemStack>> lastWon;

    /** The pocket device: one chair, and every path below collapses to what it always was. */
    public BlackjackSession(SessionHost host) {
        this(host, 1);
    }

    public BlackjackSession(SessionHost host, int seats) {
        super(host, Math.min(Math.max(1, seats), BlackjackTable.MAX_SEATS));
        this.doubleEscrow = new ItemStack[seats()];
        java.util.Arrays.fill(this.doubleEscrow, ItemStack.EMPTY);
        this.doubledChips = new boolean[seats()];
        this.ready = new boolean[seats()];
    }

    /** Read-out 0: who is ready, as a bitmask. Read-out 1: seconds left on the countdown. */
    public static final int READOUT_READY = 0;
    public static final int READOUT_COUNTDOWN = 1;

    @Override
    public int readout(int index) {
        if (index == READOUT_READY) {
            int mask = 0;
            for (int seat = 0; seat < ready.length; seat++) if (ready[seat]) mask |= 1 << seat;
            return mask;
        }
        if (index == READOUT_COUNTDOWN) return countdownSeconds();
        return 0;
    }

    private int countdownSeconds() {
        if (readyDeadlineTick <= 0) return 0;
        try {
            long left = readyDeadlineTick - host.hostLevel().getGameTime();
            return (int) Math.max(0, (left + 19) / 20);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public int turnSeat() {
        return table == null || state != GameState.ROLLING ? -1 : table.turn();
    }

    /** What each chair has put in its box, so every seat's bet shows under it. */
    @Override
    public int stakeMilli(int index) {
        if (index < 0 || index >= seats()) return -1;
        ItemStack stack = state.acceptsItems() ? slotStackOf(index) : escrowOf(index);
        if (stack.isEmpty()) return -1;
        long value = com.itemcasino.chips.ChipCards.isCard(stack)
                ? Chips.valueOfCents(stakeIsChipsAt(index) && !state.acceptsItems()
                        ? stakeCentsOf(index)
                        : Chips.centsOfChips(effectiveBetChips(stack, index)))
                : valueOf(stack);
        if (value <= 0) return -1;
        return (int) Math.min(Integer.MAX_VALUE, value / 1000L);
    }

    private ItemStack slotStackOf(int index) {
        net.minecraft.world.SimpleContainer container = seatContainer(index);
        return container == null ? ItemStack.EMPTY : container.getItem(0);
    }

    /** Every chair that has something in its box right now. */
    private boolean[] seatsWithAStake() {
        boolean[] playing = new boolean[seats()];
        for (int index = 0; index < playing.length; index++) {
            playing[index] = !slotStackOf(index).isEmpty();
        }
        return playing;
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_BLACKJACK; }

    @Nullable public BlackjackTable table() { return table; }

    @Nullable public BlackjackTable restoredHand() { return restoredHand; }

    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        boolean anyone = false;
        for (int index = 0; index < seats(); index++) {
            // Changing what is in a box withdraws that chair's word that it was ready to play it.
            if (slotStackOf(index).isEmpty()) ready[index] = false;
            else anyone = true;
        }
        if (!anyone) {
            java.util.Arrays.fill(ready, false);
            readyDeadlineTick = 0;
        }
        setState(anyone ? GameState.ARMED : GameState.IDLE);
    }

    /**
     * A chair leaving the table takes its word with it, so the others are not held to a hand that
     * one of them is no longer at.
     */
    @Override
    public void onMenuClosed(Player player) {
        int index = seatIndex(player);
        if (index >= 0 && index < ready.length && state.acceptsItems()) {
            ready[index] = false;
            host.markDirty();
        }
        super.onMenuClosed(player);
    }

    public boolean isReady(int index) {
        return index >= 0 && index < ready.length && ready[index];
    }

    /**
     * How many units of the original wager are riding on this hand: one normally, two after a
     * double. The screen multiplies the sealed slot's ghost by it, because a doubled bet that still
     * shows the original stack is a lie about how much is at stake -- and it is at stake, the
     * collateral came out of the player's inventory the moment they pressed the button.
     */
    @Override
    public int option() { return option(0); }

    @Override
    public int option(@Nullable Player viewer) {
        int index = seatIndex(viewer);
        return option(index < 0 ? 0 : index);
    }

    private int option(int index) {
        if (index < 0 || index >= seats()) return 1;
        return doubleEscrow[index].isEmpty() && !doubledChips[index] ? 1 : 2;
    }

    /** A doubled chip hand has twice the chips at stake, on the one card. */
    @Override
    protected long committedStakeCents() { return committedStakeCents(0); }

    private long committedStakeCents(int index) {
        long staked = stakeCentsOf(index);
        return index < doubledChips.length && doubledChips[index] ? staked * 2 : staked;
    }

    /**
     * Someone at the table pressed Deal. Every chair with a stake in its box is dealt in; an empty
     * box sits the hand out. Only the boxes are read — there is no separate "ready", because a chip
     * card or a stack in the box <em>is</em> the bet, and at a real table the dealer deals when the
     * bets are down.
     */
    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
        if (!isSeated(player)) return false;
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }
        ValuationSnapshot snapshot = ValuationEngine.snapshot();

        // The presser's own box is checked out loud, so they are told why it was refused. Everyone
        // else's is checked silently: they may not even be looking at the screen.
        int own = seatIndex(player);
        if (own >= 0 && !slotStackOf(own).isEmpty() && !checkStakeAt(player, own, snapshot)) {
            return false;
        }

        boolean[] playing = new boolean[seats()];
        int count = 0;
        for (int index = 0; index < playing.length; index++) {
            if (acceptableStake(index, snapshot)) { playing[index] = true; count++; }
        }
        if (count == 0) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.no_stake"), true);
            return false;
        }

        return startHand(player, playing, snapshot);
    }

    /**
     * The lobby: a chair says it is ready to play what is in its box.
     *
     * <p>The first one to say so starts a countdown. The hand is dealt when every chair with a bet
     * down is ready, or when the countdown runs out, to whoever is ready by then. Pressing again
     * takes the word back, which is why a chair can change its mind right up until the cards come
     * out. A chair that says nothing simply sits the hand out with its bet still in its box.
     *
     * <p>Only at a table that seats more than one. A pocket device deals the moment you press it.
     */
    private boolean saysReady(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
        if (!isSeated(player)) return false;
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }
        int index = seatIndex(player);
        if (index < 0 || index >= ready.length) return false;
        if (slotStackOf(index).isEmpty()) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.no_stake"), true);
            return false;
        }
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!checkStakeAt(player, index, snapshot)) return false;

        ready[index] = !ready[index];
        if (ready[index] && readyDeadlineTick == 0) {
            readyDeadlineTick = host.hostLevel().getGameTime()
                    + CasinoConfig.SERVER.readySeconds.get() * 20L;
        }
        if (!anyReady()) readyDeadlineTick = 0;
        host.markDirty();
        touch();
        if (everyBetIsReady()) return startHand(player, readySeats(), snapshot);
        return true;
    }

    private boolean anyReady() {
        for (boolean r : ready) if (r) return true;
        return false;
    }

    /** Every chair with something in its box has said it is ready, and at least one has. */
    private boolean everyBetIsReady() {
        boolean any = false;
        for (int index = 0; index < seats(); index++) {
            if (slotStackOf(index).isEmpty()) continue;
            if (!ready[index]) return false;
            any = true;
        }
        return any;
    }

    private boolean[] readySeats() {
        boolean[] playing = new boolean[seats()];
        for (int index = 0; index < seats(); index++) {
            playing[index] = ready[index] && !slotStackOf(index).isEmpty();
        }
        return playing;
    }

    /**
     * Takes the stakes and deals. Shared by the one-chair table, which starts on the button, and by
     * the lobby, which starts when everyone is ready or the countdown runs out.
     */
    private boolean startHand(@Nullable ServerPlayer initiator, boolean[] playing,
                              ValuationSnapshot snapshot) {
        this.frozenSnapshot = snapshot;
        this.sessionId++;
        java.util.Arrays.fill(doubledChips, false);
        java.util.Arrays.fill(doubleEscrow, ItemStack.EMPTY);

        // Every chair's stake moves in the same pass: there is no tick between the first escrow and
        // the last, so the table cannot be left holding one stake for a hand that never started.
        for (int index = 0; index < playing.length; index++) {
            if (!playing[index]) continue;
            escrowStakeFor(index);
            setSeatOwner(index);
        }
        if (!setState(GameState.LOCKED)) return false;
        armAcknowledgement(0L);
        // Seat 0's owner, never whoever pressed the button: wagerOwner is what everything seat 0 is
        // owed follows -- its payout, its refund, its stats. Named after the presser, a player who
        // dealt for the table collected the first chair's winnings along with their own.
        this.wagerOwner = host.seatId(0);
        java.util.Arrays.fill(ready, false);
        readyDeadlineTick = 0;
        touch();
        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), 0));
        return deal(initiator, playing);
    }

    /** The countdown runs on the server, so a client that never reports back cannot stall a table. */
    @Override
    public void tick() {
        if (seats() > 1 && state == GameState.ARMED && readyDeadlineTick > 0
                && host.hostLevel().getGameTime() >= readyDeadlineTick) {
            boolean[] playing = readySeats();
            readyDeadlineTick = 0;
            boolean any = false;
            for (boolean p : playing) if (p) { any = true; break; }
            if (any) startHand(null, playing, ValuationEngine.snapshot());
            else java.util.Arrays.fill(ready, false);
            host.markDirty();
        }
        super.tick();
    }

    /** The presser's own box, refused out loud. */
    private boolean checkStakeAt(ServerPlayer player, int index, ValuationSnapshot snapshot) {
        ItemStack stack = slotStackOf(index);
        if (ChipCards.isCard(stack)) {
            if (effectiveBetChips(stack, index) < 1) {
                player.displayClientMessage(Component.translatable("itemcasino.reject.no_chips"), true);
                return false;
            }
            return true;
        }
        StackValuator.Rejection rejection = StackValuator.reject(stack, snapshot);
        if (rejection != null) {
            player.displayClientMessage(Component.translatable(rejection.translationKey()), true);
            return false;
        }
        return true;
    }

    /** The same question asked of a chair whose player may not even be looking. */
    private boolean acceptableStake(int index, ValuationSnapshot snapshot) {
        ItemStack stack = slotStackOf(index);
        if (stack.isEmpty()) return false;
        if (ChipCards.isCard(stack)) return effectiveBetChips(stack, index) >= 1;
        return StackValuator.reject(stack, snapshot) == null;
    }

    /** Remembers whose money is in a chair, so the payout and the stats follow the player, not the seat. */
    private void setSeatOwner(int index) {
        java.util.UUID who = host.seatId(index);
        Seat chair = seat(index);
        if (chair != null) chair.setOwner(who);
        // Seat 0's owner is the base class's wagerOwner, set by beginCommit.
    }

    public boolean deal(@Nullable ServerPlayer player, boolean[] playing) {
        if (state != GameState.LOCKED) return false;

        RandomSource random = host.random();
        long seed = random.nextLong();
        this.handSeed = seed == 0L ? 1L : seed;
        this.handRules = rules();
        this.handActions.clear();
        this.handPlaying = maskOf(playing);
        this.table = new BlackjackTable(handRules, new SeededRoller(handSeed), seats());
        this.table.deal(playing);
        this.shownCards = 0;
        this.shownDealerCards = 0;
        this.revealing = false;
        host.markDirty();
        if (!setState(GameState.ROLLING)) return false;
        resetActionDeadline();

        if (CasinoConfig.SERVER.logSettlements.get()) {
            StringBuilder boxes = new StringBuilder();
            for (int index = 0; index < seats(); index++) {
                if (!playing[index]) continue;
                ItemStack chairEscrow = escrowOf(index);
                boxes.append(' ').append(index).append('=').append(chairEscrow.getCount()).append('x')
                        .append(BuiltInRegistries.ITEM.getKey(chairEscrow.getItem()))
                        .append('/').append(stakeCentsOf(index)).append('c');
            }
            ItemCasino.AUDIT.info("[wager] {} blackjack session={} seats{}",
                    player == null ? "countdown" : player.getName().getString(), sessionId, boxes);
        }
        if (table.phase() == BlackjackPhase.SETTLED) beginReveal();   // naturals can end it at once
        else broadcastState();
        return true;
    }

    private static int maskOf(boolean[] playing) {
        int mask = 0;
        for (int i = 0; i < playing.length; i++) if (playing[i]) mask |= 1 << i;
        return mask;
    }

    private boolean[] playingFromMask(int mask) {
        boolean[] playing = new boolean[seats()];
        for (int i = 0; i < playing.length; i++) playing[i] = (mask & (1 << i)) != 0;
        return playing;
    }

    /**
     * Legality is re-derived here from the server's own hand; the mask the client was given is a
     * courtesy for greying out buttons, never an authorisation. A player may only act on their own
     * chair, and only when the table says it is that chair's turn.
     */
    public boolean act(ServerPlayer player, long claimedSession, @Nullable BlackjackAction action) {
        if (state != GameState.ROLLING || table == null) return false;
        if (claimedSession != sessionId || action == null) return false;
        int index = seatIndex(player);
        if (index < 0 || index != table.turn()) return false;
        if (!action.isIn(legalMaskFor(player))) return false;

        if (action == BlackjackAction.DOUBLE && !takeDoubleCollateral(player, index)) return false;
        if (!table.apply(index, action)) {
            // Unreachable in practice, but holding a second stake for a hand that never doubled
            // would quietly eat it.
            if (action == BlackjackAction.DOUBLE) refundDoubleCollateral(player, index);
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

    /** The table's mask for this player's chair, minus DOUBLE when they cannot cover a second stake. */
    public int legalMaskFor(@Nullable Player player) {
        if (table == null) return 0;
        int index = seatIndex(player);
        if (index < 0) return 0;
        int mask = table.legalMask(index);
        if (player != null && BlackjackAction.DOUBLE.isIn(mask) && !canAffordDouble(player, index)) {
            mask &= ~BlackjackAction.DOUBLE.bit();
        }
        return mask;
    }

    /**
     * The hand is over on the server. Every viewer is sent the whole of it — the hole card, the
     * dealer's draws, every chair's result — and the payouts are held back for as long as a client
     * takes to lay those cards down, or until a client says it has.
     */
    private void beginReveal() {
        if (table == null || table.phase() != BlackjackPhase.SETTLED) {
            settle();
            return;
        }
        revealing = true;
        int dealt = 0;
        for (int index = 0; index < seats(); index++) dealt += table.hand(index).cards().size();
        int ticks = com.itemcasino.core.game.blackjack.DealClock.revealTicks(
                shownCards, shownDealerCards, dealt, table.dealer().cards().size());
        deadlineTick = host.hostLevel().getGameTime() + ticks + 40L;
        armAcknowledgement(ticks);
        host.markDirty();

        host.broadcast(settledFactory());
    }

    /** Every chair's hand, for one packet. */
    private List<SeatHand> handsOf(BlackjackTable t, boolean settled) {
        List<SeatHand> hands = new ArrayList<>(seats());
        for (int index = 0; index < seats(); index++) {
            if (!t.isPlaying(index)) continue;
            Settlement seatSettlement = settled ? t.settlement(index) : null;
            hands.add(new SeatHand(index, List.copyOf(t.hand(index).cards()), t.hand(index).total(),
                    nameOfSeat(index),
                    seatSettlement == null ? -1 : seatSettlement.outcome().ordinal(),
                    seatSettlement == null ? option(index) : seatSettlement.betUnits()));
        }
        return hands;
    }

    private String nameOfSeat(int index) {
        ServerPlayer sitting = host.seatedPlayer(index);
        return sitting == null ? "" : sitting.getName().getString();
    }

    /** The settled hand, for every viewer or for one who arrives during the reveal. */
    private java.util.function.IntFunction<net.minecraft.network.protocol.common.custom.CustomPacketPayload> settledFactory() {
        BlackjackTable t = table;
        List<SeatHand> hands = handsOf(t, true);
        List<Card> dealerFinal = List.copyOf(t.dealer().cards());
        int dealerTotal = t.dealer().total();
        long id = sessionId;
        return container -> new S2CBlackjackSettled(container, id, hands, dealerFinal, dealerTotal);
    }

    @Override
    public boolean commitWager(ServerPlayer player) {
        return seats() > 1 ? saysReady(player) : placeWager(player);
    }

    @Override
    public boolean acknowledge(long claimedSession) { return finishReveal(claimedSession); }

    /** A doubled item bet stakes the escrow and the collateral, both of the same kind. */
    @Override
    protected long stakedItemCount() {
        return (long) escrow.getCount() + doubleEscrow[0].getCount();
    }

    private long stakedItemCountAt(int index) {
        return (long) escrowOf(index).getCount() + doubleEscrow[index].getCount();
    }

    /**
     * Someone opened the table while a hand is in play: a player back after closing the screen, or
     * a neighbour sitting down to watch. Their client dropped the hand when the screen closed, so it
     * is sent again, with the session id their buttons must quote; during the reveal, the whole
     * settled hand.
     */
    @Override
    public void onViewerOpened(ServerPlayer player) {
        if (state != GameState.ROLLING || table == null) return;
        sendTo(player, id -> new S2CSessionStarted(id, sessionId, gameType(), 0));
        if (revealing && table.phase() == BlackjackPhase.SETTLED) {
            sendTo(player, settledFactory());
        } else {
            sendTo(player, stateFactoryFor(player));
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
     * A player who lets the clock run out is stood, and the turn moves on. Only once the whole hand
     * is over does the reveal start; a reveal that nobody acknowledges is settled outright.
     */
    @Override
    protected void onDeadline() {
        if (state == GameState.ROLLING && !revealing && table != null
                && table.phase() == BlackjackPhase.PLAYER_TURN) {
            int stood = table.turn();
            table.forceStand(stood);
            if (stood >= 0) handActions.add(BlackjackAction.STAND.ordinal());
            touch();
            resetActionDeadline();
            host.markDirty();
            if (table.phase() == BlackjackPhase.SETTLED) beginReveal(); else broadcastState();
            return;
        }
        forceSettle();
    }

    @Override
    public void forceSettle() {
        if (state != GameState.ROLLING) return;
        // Standing is never worse than forfeiting, so an away player is not punished for the
        // server's impatience. Every chair still owed a decision is stood, in turn, until the
        // dealer has played.
        int guard = 0;
        while (table != null && table.phase() == BlackjackPhase.PLAYER_TURN && guard++ < 16) {
            int stood = table.turn();
            if (!table.forceStand(stood)) break;
            handActions.add(BlackjackAction.STAND.ordinal());
        }
        settle();
    }

    private void settle() {
        if (!setState(GameState.SETTLING)) return;
        revealing = false;
        materialiseDecidedOutcome();

        for (int index = 0; index < seats(); index++) {
            if (escrowOf(index).isEmpty()) continue;
            boolean chips = stakeIsChipsAt(index);
            long staked = chips ? Chips.valueOfCents(committedStakeCents(index))
                    : Fixed.add(valueOf(escrowOf(index)), valueOf(doubleEscrow[index]));
            long returned = chips
                    ? Fixed.add(valueOf(payoutOf(index)), Chips.valueOfCents(chipPayoutOf(index)))
                    : valueOf(payoutOf(index));
            recordOutcome(ownerOfSeat(index), staked, returned);
            // Both halves of a doubled bet are at stake, so both count toward what was lost.
            if (chips) bankChipLossFor(index, committedStakeCents(index));
            else bankLossFor(index, stakedItemCountAt(index));
        }

        Settlement own = table == null ? null : table.settlement(0);
        decidedWin = own != null && own.outcome().isWin();
        captureSeatResults();

        for (int index = 0; index < seats(); index++) {
            setEscrowOf(index, ItemStack.EMPTY);
            doubleEscrow[index] = ItemStack.EMPTY;
            doubledChips[index] = false;
        }
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        returnCardsToSlots();
        // The other chairs have no buffer anyone can claim from: what they won goes to them now.
        for (int index = 1; index < seats(); index++) handOverSeat(index);
        // A card handed back into its box is a bet waiting to be made, but the box was filled with
        // its callback held down, so the table's state is re-derived here, once, explicitly.
        if (state.acceptsItems()) onWagerChanged();
        touch();

        if (CasinoConfig.SERVER.logSettlements.get()) {
            StringBuilder results = new StringBuilder();
            for (int index = 0; index < seats(); index++) {
                Settlement seatSettlement = table == null ? null : table.settlement(index);
                if (seatSettlement == null) continue;
                results.append(' ').append(index).append('=').append(seatSettlement.outcome())
                        .append('x').append(seatSettlement.betUnits());
            }
            ItemCasino.AUDIT.info("[settle] blackjack session={} seats{} payout={}",
                    sessionId, results, payout);
        }
        table = null;
        clearHandRecord();
        broadcastPayout();
    }

    /**
     * What to tell each chair when the cards are down.
     *
     * <p>One banner for the whole table would mean three players watching seat 0's result: the
     * screen only shows the banner to the chair the packet names. So each chair's tier, amount and
     * winnings are taken here, while the hand is still in hand, and sent to that chair alone.
     */
    private void captureSeatResults() {
        int count = seats();
        lastTier = new byte[count];
        lastCents = new long[count];
        lastWon = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Settlement settlement = table == null ? null : table.settlement(index);
            boolean chips = stakeIsChipsAt(index);
            long paid = chipPayoutOf(index);
            long staked = committedStakeCents(index);
            boolean won = settlement != null && settlement.outcome().isWin();
            boolean returned = !payoutOf(index).isEmpty() || paid > 0;
            byte tier;
            if (won) {
                tier = chips
                        ? (paid >= 10 * Math.max(1, staked)
                                ? com.itemcasino.network.s2c.S2CPayoutReady.TIER_BIG
                                : com.itemcasino.network.s2c.S2CPayoutReady.TIER_WIN)
                        : com.itemcasino.network.s2c.S2CPayoutReady.TIER_WIN;
            } else {
                tier = returned ? com.itemcasino.network.s2c.S2CPayoutReady.TIER_RETURN
                        : com.itemcasino.network.s2c.S2CPayoutReady.TIER_NONE;
            }
            lastTier[index] = tier;
            lastCents[index] = chips && won ? paid : 0L;
            List<ItemStack> summary =
                    new ArrayList<>(PayoutResolver.summarise(payoutOf(index)));
            summary.removeIf(ChipCards::isCard);
            lastWon.add(summary);
        }
    }

    /**
     * One banner per chair, each addressed to the chair it belongs to. A solo table keeps the shared
     * one it always used.
     */
    @Override
    protected void broadcastPayout() {
        if (seats() <= 1 || lastTier == null || lastCents == null || lastWon == null) {
            super.broadcastPayout();
            return;
        }
        final byte[] tiers = lastTier;
        final long[] cents = lastCents;
        final List<List<ItemStack>> won = lastWon;
        final long id = sessionId;
        host.broadcastPerPlayer(viewer -> {
            int index = seatIndex(viewer);
            if (index < 0 || index >= tiers.length) {
                return container -> new com.itemcasino.network.s2c.S2CPayoutReady(container, id,
                        List.of(), (byte) -1, com.itemcasino.network.s2c.S2CPayoutReady.TIER_NONE, 0L);
            }
            List<ItemStack> stacks = won.get(index);
            byte tier = tiers[index];
            long amount = cents[index];
            return container -> new com.itemcasino.network.s2c.S2CPayoutReady(container, id, stacks,
                    (byte) index, tier, amount);
        });
        handOverSettledPayout();
    }

    private void clearHandRecord() {
        handSeed = 0L;
        handActions.clear();
        handRules = null;
        handPlaying = 0;
    }

    /** Rebuilds the hand in flight from its seed and actions, or null when there is none to rebuild. */
    @Nullable
    private BlackjackTable rebuildHand() {
        if (handSeed == 0L || handRules == null || handPlaying == 0) return null;
        BlackjackTable rebuilt = new BlackjackTable(handRules, new SeededRoller(handSeed), seats());
        rebuilt.deal(playingFromMask(handPlaying));
        for (int ordinal : handActions) {
            BlackjackAction action = BlackjackAction.byId(ordinal);
            // Applied to whichever chair the rebuilt table says is on turn: the turn order is the
            // table's own, so replaying the actions in order replays them to the same chairs.
            if (action == null || !rebuilt.apply(rebuilt.turn(), action)) {
                ItemCasino.LOGGER.error("Blackjack session {}: action {} did not replay; standing from here",
                        sessionId, ordinal);
                break;
            }
        }
        return rebuilt;
    }

    /**
     * After a reload: rebuild the hand, stand whatever chairs were still owed a decision, and settle
     * what that produces. Only a session saved before hands were recorded falls back to a refund.
     */
    @Override
    protected void repairAfterLoad() {
        if (state.holdsEscrow() && table == null) {
            BlackjackTable rebuilt = rebuildHand();
            if (rebuilt != null) {
                int guard = 0;
                while (rebuilt.phase() == BlackjackPhase.PLAYER_TURN && guard++ < 16) {
                    if (!rebuilt.forceStand(rebuilt.turn())) break;
                }
                this.table = rebuilt;
                this.restoredHand = rebuilt;
                ItemCasino.LOGGER.info("Blackjack session {} restored from its seed and stood ({})",
                        sessionId, rebuilt.settlement(0) == null ? "unsettled" : rebuilt.settlement(0).outcome());
            }
        }
        super.repairAfterLoad();
        if (!state.holdsEscrow()) {
            // The base repair empties seat 0's escrow but knows nothing of the double-down
            // collateral, nor of the other chairs. Left set, they were handed back a second time
            // when the table was broken.
            for (int index = 0; index < seats(); index++) {
                doubleEscrow[index] = ItemStack.EMPTY;
                doubledChips[index] = false;
            }
            for (int index = 1; index < seats(); index++) {
                setEscrowOf(index, ItemStack.EMPTY);
                handOverSeat(index);
            }
        }
        table = null;
        clearHandRecord();
    }

    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        for (int index = 0; index < seats(); index++) materialiseSeat(index);
    }

    private void materialiseSeat(int index) {
        ItemStack chairEscrow = escrowOf(index);
        if (chairEscrow.isEmpty()) return;
        Settlement settlement = table == null ? null : table.settlement(index);
        if (stakeIsChipsAt(index)) {
            // payNumerator already folds in a double, measured against the original stake. With
            // nothing to rebuild the hand is void: the whole stake, doubled or not, comes back.
            long staked = committedStakeCents(index);
            long paid = settlement == null ? staked
                    : Chips.payout(stakeCentsOf(index), settlement.payNumerator(), settlement.payDenominator());
            payChipsFor(index, paid, staked);
            return;
        }
        if (settlement == null) {
            // Only reachable for a session saved before hands were recorded: nothing to rebuild.
            List<ItemStack> refund = new ArrayList<>(4);
            PayoutResolver.addSplit(refund, chairEscrow, chairEscrow.getCount());
            if (!doubleEscrow[index].isEmpty()) {
                PayoutResolver.addSplit(refund, doubleEscrow[index], doubleEscrow[index].getCount());
            }
            setPayoutFor(index, refund);
            return;
        }
        // payNumerator already folds in the doubled bet, so this is measured against the ORIGINAL
        // stack: a won double is 4x, a natural 5/2, a surrender 1/2.
        setPayoutFor(index, PayoutResolver.multiply(chairEscrow, settlement.payNumerator(),
                settlement.payDenominator(), snapshot()));
    }

    public void broadcastState() {
        if (table == null) return;
        int dealt = 0;
        for (int index = 0; index < seats(); index++) dealt += table.hand(index).cards().size();
        shownCards = dealt;
        shownDealerCards = table.dealerVisible().cards().size() + (table.holeHidden() ? 1 : 0);
        // Built per recipient: the legal mask is the reader's own, never the chair on turn's.
        host.broadcastPerPlayer(this::stateFactoryFor);
    }

    /** The hand as it stands, as one viewer is shown it. */
    private java.util.function.IntFunction<net.minecraft.network.protocol.common.custom.CustomPacketPayload> stateFactoryFor(
            @Nullable ServerPlayer viewer) {
        BlackjackTable t = table;
        int remaining = (int) Math.max(0, deadlineTick - host.hostLevel().getGameTime());
        List<SeatHand> hands = handsOf(t, false);
        List<Card> dealerVisible = List.copyOf(t.dealerVisible().cards());
        byte phase = (byte) t.phase().ordinal();
        int dealerTotal = t.holeHidden() ? t.dealerVisible().total() : t.dealer().total();
        boolean hidden = t.holeHidden();
        int turn = t.turn();
        int mask = legalMaskFor(viewer);
        long id = sessionId;
        return container -> new S2CBlackjackState(container, id, phase, hands, dealerVisible,
                hidden, mask, turn, dealerTotal, remaining);
    }

    // ------------------------------------------------------------------ double-down collateral

    private boolean canAffordDouble(Player player, int index) {
        if (stakeIsChipsAt(index)) {
            return !doubledChips[index]
                    && ChipCards.balance(escrowOf(index)) >= stakeCentsOf(index) * 2;
        }
        return countMatching(player, escrowOf(index)) >= escrowOf(index).getCount();
    }

    private boolean takeDoubleCollateral(ServerPlayer player, int index) {
        ItemStack chairEscrow = escrowOf(index);
        if (stakeIsChipsAt(index)) {
            if (!canAffordDouble(player, index)) return false;
            doubledChips[index] = true;
            host.markDirty();
            return true;
        }
        int needed = chairEscrow.getCount();
        if (countMatching(player, chairEscrow) < needed) return false;

        Inventory inventory = player.getInventory();
        int remaining = needed;
        // The 36 carried slots only. getContainerSize() also counts the armour, the offhand, the body
        // armour and the saddle, so doubling a bet on a chestplate used to take the one being worn.
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!ItemStack.isSameItemSameComponents(stack, chairEscrow)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            remaining -= take;
        }
        if (remaining > 0) {
            ItemCasino.LOGGER.error("Double-down collateral came up {} short; refunding", remaining);
            ItemStack refund = chairEscrow.copy();
            refund.setCount(needed - remaining);
            giveTo(player, List.of(refund));
            return false;
        }
        ItemStack taken = chairEscrow.copy();
        taken.setCount(needed);
        doubleEscrow[index] = taken;
        host.markDirty();
        return true;
    }

    private void refundDoubleCollateral(ServerPlayer player, int index) {
        if (doubledChips[index]) {
            doubledChips[index] = false;
            host.markDirty();
            return;
        }
        if (doubleEscrow[index].isEmpty()) return;
        ItemStack back = doubleEscrow[index].copy();
        doubleEscrow[index] = ItemStack.EMPTY;
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
        // Seat 0's own holdings go to seat 0, for the same reason: at a shared table the player who
        // broke it, or closed the last screen, is not necessarily the one whose diamonds these are.
        // Done here so the base class finds seat 0 already empty.
        if (seats() > 1) {
            java.util.UUID first = ownerOfSeat(0);
            List<ItemStack> mine = new ArrayList<>(3);
            if (!wagerContainer().getItem(0).isEmpty()) mine.add(wagerContainer().removeItemNoUpdate(0));
            if (!escrow.isEmpty()) { mine.add(escrow.copy()); escrow = ItemStack.EMPTY; }
            mine.addAll(payout);
            payout.clear();
            giveTo(first, mine);
        }

        // Each chair's second stake goes back to the chair that put it up, not to whoever is here.
        for (int index = 0; index < seats(); index++) {
            if (doubleEscrow[index].isEmpty()) continue;
            ItemStack back = doubleEscrow[index].copy();
            doubleEscrow[index] = ItemStack.EMPTY;
            if (index == 0) giveTo(to, List.of(back)); else giveTo(ownerOfSeat(index), List.of(back));
        }
        java.util.Arrays.fill(doubledChips, false);
        table = null;
        clearHandRecord();
        super.liquidate(to);
    }

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.store("double_escrow", ItemStack.OPTIONAL_CODEC, doubleEscrow[0]);
        out.putBoolean("doubled_chips", doubledChips[0]);
        for (int index = 1; index < seats(); index++) {
            out.store("double_escrow_" + index, ItemStack.OPTIONAL_CODEC, doubleEscrow[index]);
            out.putBoolean("doubled_chips_" + index, doubledChips[index]);
        }
        if (handSeed != 0L && handRules != null) {
            out.putLong("hand_seed", handSeed);
            out.putInt("hand_playing", handPlaying);
            out.store("hand_actions", Codec.INT.listOf(), List.copyOf(handActions));
            out.store("hand_rules", Codec.INT.listOf(), List.of(handRules.decks(),
                    handRules.dealerHitsSoft17() ? 1 : 0, handRules.allowDouble() ? 1 : 0,
                    handRules.allowSurrender() ? 1 : 0, handRules.dealerPeek() ? 1 : 0));
        }
    }

    @Override
    public void load(ValueInput in) {
        doubleEscrow[0] = in.read("double_escrow", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        doubledChips[0] = in.getBooleanOr("doubled_chips", false);
        for (int index = 1; index < seats(); index++) {
            doubleEscrow[index] = in.read("double_escrow_" + index, ItemStack.OPTIONAL_CODEC)
                    .orElse(ItemStack.EMPTY);
            doubledChips[index] = in.getBooleanOr("doubled_chips_" + index, false);
        }
        handSeed = in.getLongOr("hand_seed", 0L);
        // A hand saved before the table seated three played seat 0 alone.
        handPlaying = in.getIntOr("hand_playing", handSeed != 0L ? 1 : 0);
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
        super.load(in);   // runs repairAfterLoad, which needs the double escrows above
    }
}
