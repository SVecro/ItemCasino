package com.itemcasino.session;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.GameState;
import com.itemcasino.chips.ChipCards;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.core.game.SessionMachine;
import net.minecraft.network.chat.Component;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.jackpot.Jackpot;
import com.itemcasino.player.CasinoStats;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.network.s2c.S2CPayoutReady;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One wager, from the moment a stack lands in the slot to the moment the winnings are collected.
 *
 * <p>This used to live inside the block entity. A portable device has no block entity, so the
 * session had to come out — and having it out is the better shape anyway: the rules are now
 * testable against a fake {@link SessionHost} instead of only against a running world.
 */
public abstract class CasinoSession {

    protected final SessionHost host;

    protected final SimpleContainer wager = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            host.markDirty();
            // Escrowing and refunding move the stack themselves; letting the slot callback run
            // during those would re-derive the state from a half-applied mutation. That reentrancy
            // once drove a committed wager back to IDLE and stranded the escrow forever.
            if (!suppressSlotCallback) onWagerChanged();
        }
    };

    protected GameState state = GameState.IDLE;
    protected long sessionId;
    protected int frozenPpm;
    protected boolean decidedWin;
    protected int spinTicks;
    protected long deadlineTick;
    protected long lastTouchTick;
    protected ItemStack escrow = ItemStack.EMPTY;
    /**
     * Who committed the wager in flight, by UUID, captured at commit. Ownership of the payout and
     * the stats entry both hang off this rather than off whoever is looking at the screen later.
     */
    @Nullable protected UUID wagerOwner;
    /** Before this game tick, an animation acknowledgement is too early to believe. Not persisted. */
    protected long ackNotBefore;
    protected final List<ItemStack> payout = new ArrayList<>();

    /** Whole chips the seated player wants to bet when the slot holds a chip card. A table setting. */
    protected long betChips = 10;
    /** The chips staked by the wager in escrow, in cents; zero when the wager is items. */
    protected long stakeCents;
    /** What the last chip wager paid back, in cents: the loss banked and the stats read it. */
    protected long chipPayoutCents;
    /** The last settled chip wager came out ahead, for the win sound. Not persisted. */
    protected transient boolean chipWin;

    /** The value table this wager was locked against; never persisted, see {@link #snapshot()}. */
    @Nullable protected transient ValuationSnapshot frozenSnapshot;

    private boolean suppressSlotCallback;

    /**
     * The chairs beyond the first. Seat 0's slot, escrow, stake, bet and payout are the fields
     * above, saved under the keys they have always used; see {@link Seat} for why that asymmetry
     * is on purpose rather than an accident.
     */
    private final Seat[] extra;

    protected CasinoSession(SessionHost host) {
        this(host, 1);
    }

    protected CasinoSession(SessionHost host, int seats) {
        this.host = host;
        this.extra = new Seat[Math.max(0, seats - 1)];
        for (int i = 0; i < extra.length; i++) extra[i] = new Seat(host, this::onWagerChanged);
    }

    // ------------------------------------------------------------------ queries

    public GameState gameState() { return state; }

    public SimpleContainer wagerContainer() { return wager; }

    public ItemStack wagerStack() { return wager.getItem(0); }

    public long sessionId() { return sessionId; }

    public int frozenPpm() { return frozenPpm; }

    public int spinTicks() { return spinTicks; }

    public boolean hasPayout() { return !payout.isEmpty(); }

    public List<ItemStack> peekPayout() { return List.copyOf(payout); }

    public ItemStack escrowView() { return escrow.copy(); }

    public boolean isSeated(@Nullable Player player) { return host.isSeated(player); }

    /** How many players this game seats. One for the solo games, two for a duel, three at cards. */
    public int seats() { return extra.length + 1; }

    /**
     * The chair at this index, or null for seat 0 and for anything out of range.
     *
     * <p>Null for seat 0 is not an oversight: the first chair has no {@link Seat} object, and every
     * caller here either handles seat 0 explicitly first or is reaching for a chair it created.
     */
    @Nullable
    protected Seat seat(int index) {
        return index >= 1 && index <= extra.length ? extra[index - 1] : null;
    }

    /** Whose chips are in this chair: who staked, or failing that whoever is sitting in it. */
    @Nullable
    protected UUID ownerOfSeat(int index) {
        if (index == 0) return wagerOwner != null ? wagerOwner : host.seatId(0);
        Seat chair = seat(index);
        UUID staked = chair == null ? null : chair.owner();
        return staked != null ? staked : host.seatId(index);
    }

    /** Which seat this player holds, or -1. Only a duel has more than one. */
    public int seatIndex(@Nullable Player player) { return host.seatIndex(player); }

    /**
     * A per-game setting the seated player chooses before committing: the dice bet, the
     * upgrader's count, the Vault's share of the pot, the number of mines. The server only accepts it
     * while the game still takes items, and freezes whatever it affects at commit.
     */
    public int option() { return 0; }

    /**
     * The same setting, for one viewer. Only a table where each chair has its own answer needs it:
     * a doubled blackjack bet is doubled for the chair that doubled it and nobody else.
     */
    public int option(@Nullable Player viewer) { return option(); }

    /** Sets that toggle. Subclasses decide when it is too late to change it. */
    public void setOption(ServerPlayer player, int value) {}

    /**
     * What a seat has staked, in thousandths of a unit, or -1 for nothing.
     *
     * <p>Scaled down from micro-units because the menu's data channel carries ints and a stack of
     * anything valuable overflows one at full precision. Three decimals is far more than a caption
     * needs, and nothing is ever paid from this number.
     */
    public int stakeMilli(int seat) { return -1; }

    /** The slot machine's decided faces, packed into one int; -1 for every other game. */
    public int reelState() { return -1; }

    /**
     * The chair that may act right now, or -1 when none may.
     *
     * <p>Only a game played in turns has one. Everywhere else every seated player acts whenever the
     * state allows it, and this stays -1.
     */
    public int turnSeat() { return -1; }

    /**
     * Six spare display ints on the menu's data channel, for numbers a game's screen needs that
     * no shared slot carries (the Vault's chosen share and prize, the mine field's multipliers).
     * Display only, like every other data slot: nothing is paid from them.
     */
    public int readout(int index) { return 0; }

    /**
     * Who may collect what is in the payout buffer: the player the payout belongs to, by UUID.
     * A duel pays the winner, not whoever asks.
     */
    public boolean canClaim(Player player) {
        UUID owner = payoutOwner();
        return owner != null ? owner.equals(player.getUUID()) : isSeated(player);
    }

    /** Whose payout this is. The committing player for every solo game; the winner of a duel. */
    @Nullable
    public UUID payoutOwner() {
        return wagerOwner != null ? wagerOwner : host.seatId(0);
    }

    @Nullable public UUID wagerOwner() { return wagerOwner; }

    public boolean stillValid(Player player) { return host.stillValid(player); }

    public boolean hasLiveWager() {
        if (state.holdsEscrow() || !escrow.isEmpty() || !payout.isEmpty()) return true;
        for (Seat chair : extra) {
            if (!chair.escrow().isEmpty() || chair.hasPayout()) return true;
        }
        return false;
    }

    /** A live wager keeps the odds it was locked against, whatever {@code /reload} does after. */
    public ValuationSnapshot snapshot() {
        return frozenSnapshot != null ? frozenSnapshot : ValuationEngine.snapshot();
    }

    /** Routed from the menu so the host can free a seat or tear a pocket session down. */
    public void onMenuClosed(Player player) {
        host.onViewerClosed(player);
    }

    /**
     * A player has just opened this session's screen, and their menu is live. Anything the screen
     * needs that no data slot carries (a blackjack hand in progress) is sent from here: the client
     * clears its state when a screen closes, so someone who closes and reopens mid-game would
     * otherwise look at an empty table until the next packet.
     */
    public void onViewerOpened(ServerPlayer player) {}

    /** Sends one packet to one viewer, addressed to the menu they have open. */
    protected static void sendTo(ServerPlayer player,
                                 java.util.function.IntFunction<net.minecraft.network.protocol.common.custom.CustomPacketPayload> factory) {
        if (player.containerMenu == null) return;
        com.itemcasino.network.CasinoNetwork.send(player, factory.apply(player.containerMenu.containerId));
    }

    // ------------------------------------------------------------------ the two commands every game answers

    /**
     * The seated player pressed the table's commit button: spin, roll, deal, ready, draw, start.
     * Every game implements it, so the packet handler needs no list of games to forget one from.
     */
    public abstract boolean commitWager(ServerPlayer player);

    /**
     * The client has finished showing the animation of session {@code claimedSession}. Only ever brings
     * a settle forward; the deadline settles without it.
     */
    public abstract boolean acknowledge(long claimedSession);

    public void touch() {
        lastTouchTick = host.hostLevel().getGameTime();
    }

    /** Nobody has acted on this session for {@code abandon_seconds}. */
    public boolean isStale() {
        long abandonTicks = CasinoConfig.SERVER.abandonSeconds.get() * 20L;
        return lastTouchTick > 0 && host.hostLevel().getGameTime() - lastTouchTick > abandonTicks;
    }

    // ------------------------------------------------------------------ chips

    /** A chips-only table refuses items in its slot: the dice and the mine field. */
    public boolean chipsOnly() { return false; }

    /** The most chips one bet may stake at this table; {@link Long#MAX_VALUE} for no limit. */
    public long maxBetChips() { return Long.MAX_VALUE; }

    /** The bet setting for a seat. Every chair chooses its own. */
    public long betChips(int seat) {
        Seat chair = seat(seat);
        return chair != null ? chair.betChips() : betChips;
    }

    /** The bet setting of whoever is looking, for the menu's data channel. */
    public long betChipsFor(@Nullable Player viewer) {
        int seat = seatIndex(viewer);
        return betChips(Math.max(0, seat));
    }

    /** Sets a seat's bet. Refused once committed, like every other setting. */
    public void setBet(ServerPlayer player, long chips) {
        if (!state.acceptsItems()) return;
        int seat = Math.max(0, seatIndex(player));
        long clamped = Math.max(1, Math.min(chips, maxBetChips()));
        if (clamped == betChips(seat)) return;
        storeBet(seat, clamped);
        host.markDirty();
        // Odds, quotes and the arming state can all depend on the stake.
        onWagerChanged();
    }

    protected void storeBet(int seat, long chips) {
        Seat chair = seat(seat);
        if (chair != null) chair.setBetChips(chips); else betChips = chips;
    }

    /** What a card would stake right now: the setting, capped by the table and by the card. */
    public long effectiveBetChips(ItemStack card, int seat) {
        if (!ChipCards.isCard(card)) return 0;
        return Math.max(0, Math.min(Math.min(betChips(seat), maxBetChips()), ChipCards.wholeChips(card)));
    }

    public long effectiveBetChips() {
        return effectiveBetChips(wagerStack(), 0);
    }

    /** True when the wager on the table, committed or not, is chips. */
    public boolean stakeIsChips() {
        if (state.acceptsItems()) return ChipCards.isCard(wagerStack());
        return stakeCents > 0 && ChipCards.isCard(escrow);
    }

    /** The chips committed, counting any doubling a game allows. Blackjack overrides it. */
    protected long committedStakeCents() {
        return stakeCents;
    }

    /** What is at stake, in value micro-units: the bet about to be made, or the one in escrow. */
    public long stakeValue() {
        if (state.acceptsItems()) {
            ItemStack stack = wagerStack();
            if (ChipCards.isCard(stack)) return Chips.valueOfCents(Chips.centsOfChips(effectiveBetChips()));
            if (stack.isEmpty()) return -1;
            long value = StackValuator.value(stack, snapshot());
            return value == Fixed.INF ? -1 : value;
        }
        if (stakeIsChips()) return Chips.valueOfCents(committedStakeCents());
        return escrow.isEmpty() ? -1 : valueOf(escrow);
    }

    /**
     * Whether the slot holds a stake this table can take, telling the player why not. Chips first:
     * a card is never valued as an item.
     */
    protected boolean checkStake(ServerPlayer player, ValuationSnapshot snapshot) {
        ItemStack stack = wagerStack();
        if (ChipCards.isCard(stack)) {
            if (effectiveBetChips() < 1) {
                player.displayClientMessage(Component.translatable("itemcasino.reject.no_chips"), true);
                return false;
            }
            return true;
        }
        if (chipsOnly()) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.chips_only"), true);
            return false;
        }
        StackValuator.Rejection rejection = StackValuator.reject(stack, snapshot);
        if (rejection != null) {
            player.displayClientMessage(Component.translatable(rejection.translationKey()), true);
            return false;
        }
        return true;
    }

    /**
     * Moves the stake into escrow. A chip stake moves the whole card, balance untouched, and records
     * how much of it is at stake: a refund is then simply the card coming back as it went in, and
     * only a settlement ever writes a new balance.
     */
    protected ItemStack escrowStake() {
        boolean chips = ChipCards.isCard(wagerStack());
        long bet = chips ? Chips.centsOfChips(effectiveBetChips()) : 0L;
        ItemStack moved = escrowWager();
        stakeCents = bet;
        chipPayoutCents = 0;
        chipWin = false;
        return moved;
    }

    /**
     * Settles a chip wager: the escrowed card with its new balance goes into the payout buffer,
     * after any items the game has already put there. The caller hands it back to the slot once the
     * state allows it — see {@link #returnCardsToSlots}.
     *
     * @param payoutCents everything the wager pays back, stake included (0 for a loss)
     */
    protected void payChips(long payoutCents) {
        if (stakeCents <= 0 || !ChipCards.isCard(escrow)) return;
        long staked = committedStakeCents();
        chipPayoutCents = Math.max(0, payoutCents);
        chipWin = chipPayoutCents > staked;
        long balance = ChipCards.balance(escrow);
        ItemStack card = escrow.copy();
        ChipCards.setBalance(card, Chips.add(Math.max(0, balance - staked), chipPayoutCents));
        payout.add(card);
        host.markDirty();
    }

    /**
     * Puts a settled card back into the slot it was bet from, so the next bet is one click away
     * instead of a claim and a drag. Only into an empty slot, and only for this table's own wager
     * slot; anything else stays in the payout buffer to be claimed as usual.
     */
    protected void returnCardsToSlots() {
        if (payout.isEmpty()) return;
        boolean moved = false;
        for (int i = 0; i < payout.size(); i++) {
            ItemStack stack = payout.get(i);
            if (!ChipCards.isCard(stack) || !wager.getItem(0).isEmpty()) continue;
            suppressSlotCallback = true;
            try {
                wager.setItem(0, stack.copy());
            } finally {
                suppressSlotCallback = false;
            }
            payout.remove(i);
            moved = true;
            break;
        }
        if (!moved) return;
        if (payout.isEmpty() && state == GameState.PAYOUT_PENDING) setState(GameState.IDLE);
        host.markDirty();
        if (state.acceptsItems()) onWagerChanged();
    }

    /** Puts a stack into this table's own wager slot without triggering the slot callback. */
    protected void returnToSlotA(ItemStack stack) {
        suppressSlotCallback = true;
        try {
            wager.setItem(0, stack);
        } finally {
            suppressSlotCallback = false;
        }
        host.markDirty();
    }

    // ------------------------------------------------------------------ the same, one chair at a time

    /*
     * Everything above moves seat 0's money. Below is the same set, told which chair to move, so a
     * table played by three people can settle each of them on its own terms. Seat 0 still goes
     * through the fields and the save keys it always has: the twins delegate rather than duplicate,
     * so a solo table takes exactly the path it took before chairs existed.
     */

    /*
     * Seat 0 is the base fields; any other index is its Seat, or nothing at all. A chair that has no
     * Seat object -- the duel's second chair, which predates them and keeps its own fields -- reads
     * as empty and writes nowhere, rather than falling through to seat 0's money: a
     * setEscrowOf(1, EMPTY) on a duel must never be able to wipe chair A's stake.
     */

    /**
     * Whether any chair has a stake in escrow. At a shared table seat 0 may sit a hand out while
     * the others play, so seat 0's escrow alone does not say whether the table holds a wager.
     */
    public boolean holdsAnyEscrow() {
        for (int index = 0; index < seats(); index++) {
            if (!escrowOf(index).isEmpty()) return true;
        }
        return false;
    }

    protected ItemStack escrowOf(int index) {
        if (index == 0) return escrow;
        Seat chair = seat(index);
        return chair != null ? chair.escrow() : ItemStack.EMPTY;
    }

    protected void setEscrowOf(int index, ItemStack stack) {
        if (index == 0) {
            escrow = stack;
        } else {
            Seat chair = seat(index);
            if (chair == null) return;
            chair.setEscrow(stack);
        }
        host.markDirty();
    }

    protected long stakeCentsOf(int index) {
        if (index == 0) return stakeCents;
        Seat chair = seat(index);
        return chair != null ? chair.stakeCents() : 0L;
    }

    protected long chipPayoutOf(int index) {
        if (index == 0) return chipPayoutCents;
        Seat chair = seat(index);
        return chair != null ? chair.chipPayoutCents() : 0L;
    }

    protected List<ItemStack> payoutOf(int index) {
        if (index == 0) return List.copyOf(payout);
        Seat chair = seat(index);
        return chair != null ? chair.peekPayout() : List.of();
    }

    /** True when the chair's committed stake is chips rather than items. */
    protected boolean stakeIsChipsAt(int index) {
        if (index == 0) return stakeIsChips();
        Seat chair = seat(index);
        if (chair == null) return false;
        if (state.acceptsItems()) return ChipCards.isCard(chair.stack());
        return chair.stakeCents() > 0 && ChipCards.isCard(chair.escrow());
    }

    /**
     * Moves one chair's slot into its own escrow, recording what it staked. Synchronous and
     * unconditional once it starts, like {@link #escrowWager}: no tick boundary between emptying
     * the slot and recording the escrow, so the stack can never be in two places at once.
     */
    protected ItemStack escrowStakeFor(int index) {
        if (index == 0) return escrowStake();
        Seat chair = seat(index);
        if (chair == null) return ItemStack.EMPTY;
        ItemStack stack = chair.stack();
        if (stack.isEmpty()) return ItemStack.EMPTY;
        long bet = ChipCards.isCard(stack)
                ? Chips.centsOfChips(effectiveBetChips(stack, index)) : 0L;
        ItemStack moved = stack.copy();
        chair.setEscrow(moved);
        chair.quietly(() -> chair.slot().setItem(0, ItemStack.EMPTY));
        chair.setStakeCents(bet);
        chair.setChipPayoutCents(0);
        host.markDirty();
        return moved;
    }

    /** Settles one chair's chip bet: its card comes back with the new balance written on it. */
    protected void payChipsFor(int index, long payoutCents, long stakedCents) {
        if (index == 0) { payChips(payoutCents); return; }
        Seat chair = seat(index);
        if (chair == null) return;
        ItemStack chairEscrow = chair.escrow();
        if (chair.stakeCents() <= 0 || !ChipCards.isCard(chairEscrow)) return;
        long paid = Math.max(0, payoutCents);
        chair.setChipPayoutCents(paid);
        long balance = ChipCards.balance(chairEscrow);
        ItemStack card = chairEscrow.copy();
        ChipCards.setBalance(card, Chips.add(Math.max(0, balance - stakedCents), paid));
        chair.addPayout(List.of(card));
        host.markDirty();
    }

    protected void setPayoutFor(int index, List<ItemStack> stacks) {
        if (index == 0) { setPayout(stacks); return; }
        Seat chair = seat(index);
        if (chair == null) return;
        chair.takePayout();
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) if (!stack.isEmpty()) copies.add(stack.copy());
        chair.addPayout(copies);
        host.markDirty();
    }

    /** Banks whatever one chair's item bet did not pay back. */
    protected void bankLossFor(int index, long stakedCount) {
        if (index == 0) { bankLoss(stakedCount); return; }
        ItemStack chairEscrow = escrowOf(index);
        if (chairEscrow.isEmpty() || stakedCount <= 0) return;
        long paidBack = 0;
        for (ItemStack stack : payoutOf(index)) {
            if (ItemStack.isSameItemSameComponents(stack, chairEscrow)) paidBack += stack.getCount();
        }
        long lost = stakedCount - paidBack;
        if (lost > 0) com.itemcasino.jackpot.Jackpot.bank(host.hostLevel(), chairEscrow, lost);
    }

    /** Banks whatever one chair's chip bet did not pay back. */
    protected void bankChipLossFor(int index, long stakedCents) {
        long lost = stakedCents - chipPayoutOf(index);
        if (lost > 0) com.itemcasino.jackpot.Jackpot.bankChips(host.hostLevel(), lost);
    }

    /**
     * Hands one chair its winnings and puts its card back in its box.
     *
     * <p>Seat 0 keeps the old route — the shared payout buffer, the Collect button, the sweep to the
     * mailbox — because that is what every solo table still uses. The other chairs have no buffer
     * anyone can claim from, so what they won goes straight to whoever owns the chair, by UUID, and
     * reaches them through the mailbox even if they logged out while the dealer was drawing.
     */
    protected void handOverSeat(int index) {
        Seat chair = seat(index);
        if (chair == null) return;
        UUID owner = ownerOfSeat(index);
        // The card goes back in the box it was bet from, so the next hand is one click away -- but
        // only while its owner is the one sitting there. A box is whoever sits in the chair's, and a
        // card left in the box of a chair someone else now holds would be theirs to take.
        boolean ownerSits = owner != null && owner.equals(host.seatId(index));
        List<ItemStack> won = chair.takePayout();
        List<ItemStack> keep = new ArrayList<>(won.size());
        for (ItemStack stack : won) {
            if (ChipCards.isCard(stack) && ownerSits && chair.stack().isEmpty()) {
                chair.quietly(() -> chair.slot().setItem(0, stack.copy()));
            } else {
                keep.add(stack);
            }
        }
        giveTo(owner, keep);
        host.markDirty();
    }

    /** The staked value for the stats: chips, or the escrowed items. */
    protected long stakedValue() {
        return stakeIsChips() ? Chips.valueOfCents(committedStakeCents()) : valueOf(escrow);
    }

    /** What came back, for the stats: the items paid plus the chips paid. Cards are never valued. */
    protected long returnedValue() {
        long items = valueOf(payout);
        return stakeCents > 0 ? Fixed.add(items, Chips.valueOfCents(chipPayoutCents)) : items;
    }

    // ------------------------------------------------------------------ commit bookkeeping

    /**
     * Records who committed and when their animation may be acknowledged. Called by every game
     * right after the stake moves to escrow.
     *
     * @param animationTicks how long the client animation lasts; three quarters of it must pass on
     *                       the server before an acknowledgement can settle the wager early
     */
    protected void beginCommit(ServerPlayer player, long animationTicks) {
        this.wagerOwner = player.getUUID();
        armAcknowledgement(animationTicks);
    }

    protected void armAcknowledgement(long animationTicks) {
        this.ackNotBefore = host.hostLevel().getGameTime() + Math.max(0L, animationTicks) * 3L / 4L;
    }

    /** True once enough of the animation has played for the client's acknowledgement to count. */
    public boolean acknowledgementDue() {
        return host.hostLevel().getGameTime() >= ackNotBefore;
    }

    /** A stack's worth against the snapshot this wager was locked with. */
    protected long valueOf(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        long value = StackValuator.value(stack, snapshot());
        return value == Fixed.INF ? 0 : value;
    }

    protected long valueOf(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) total = Fixed.add(total, valueOf(stack));
        return total;
    }

    /** One settled wager into the committing player's stats. Safe with no level (a restore). */
    protected void recordOutcome(long staked, long returned) {
        recordOutcome(wagerOwner, staked, returned);
    }

    protected void recordOutcome(@Nullable UUID player, long staked, long returned) {
        if (player == null) return;
        ServerLevel level;
        try {
            level = host.hostLevel();
        } catch (RuntimeException e) {
            return;
        }
        if (level == null) return;
        CasinoStats.recordWager(level.getServer(), player, gameType(), staked, returned);
    }

    // ------------------------------------------------------------------ handing items back

    /**
     * The slot a chair stakes from, for a menu to bind to. Null for a place with no chair behind
     * it, which is what a table that seats fewer players than the screen has places gives back.
     */
    @Nullable
    public SimpleContainer seatContainer(int seat) {
        return slotContainer(seat);
    }

    /** The slot a seat stakes from. One for every solo game; a duel overrides it for seat B. */
    @Nullable
    protected SimpleContainer slotContainer(int seat) {
        if (seat == 0) return wager;
        Seat chair = seat(seat);
        return chair == null ? null : chair.slot();
    }

    /**
     * Empties one seat's slot, but only while nothing is committed. Used when a seat is given up:
     * the stake goes back to the player who put it there instead of waiting for the next person
     * to sit down and pick it up.
     */
    public ItemStack takeSlot(int seat) {
        if (!state.acceptsItems()) return ItemStack.EMPTY;
        return takeSlotUnchecked(seat);
    }

    /** The same, in any state. Only for tearing a session down. */
    public ItemStack takeSlotUnchecked(int seat) {
        SimpleContainer container = slotContainer(seat);
        if (container == null || container.getItem(0).isEmpty()) return ItemStack.EMPTY;
        // removeItemNoUpdate skips setChanged, so the slot callback cannot run on a half-applied
        // mutation; the state is re-derived once, explicitly, afterwards.
        ItemStack taken = container.removeItemNoUpdate(0);
        host.markDirty();
        if (state.acceptsItems()) onWagerChanged();
        return taken;
    }

    /** Takes the whole payout buffer, for delivery elsewhere (a mailbox, a spill). */
    public List<ItemStack> takePayout() {
        List<ItemStack> out = new ArrayList<>(payout);
        payout.clear();
        if (state == GameState.PAYOUT_PENDING) setState(GameState.IDLE);
        if (state == GameState.IDLE) wagerOwner = null;
        host.markDirty();
        return out;
    }

    // ------------------------------------------------------------------ state machine

    /**
     * The only way state changes outside of loading. An illegal transition is refused and logged
     * rather than applied: the table in {@link SessionMachine} is exhaustively unit-tested, so a
     * refusal means the caller is wrong, and applying it anyway is how a replayed packet becomes a
     * second payout.
     */
    protected boolean setState(GameState to) {
        if (to == state) return true;
        if (!SessionMachine.isLegal(state, to)) {
            ItemCasino.LOGGER.error("Refused illegal casino transition {} -> {} (session {})",
                    state, to, sessionId);
            return false;
        }
        state = to;
        host.markDirty();
        audit();
        return true;
    }

    protected void audit() {
        String violation = SessionMachine.violation(state, holdsAnyEscrow(), !payout.isEmpty(),
                host.seatedPlayer() != null || host.isSeated(null));
        if (violation != null && !escrow.isEmpty()) {
            ItemCasino.LOGGER.debug("Casino invariant note (session {}): {}", sessionId, violation);
        }
    }

    /**
     * Moves the wager into escrow. Synchronous and unconditional once it starts: no tick boundary,
     * no future and no packet round trip between emptying the slot and recording the escrow, so the
     * stack can never exist in two places at once.
     */
    protected ItemStack escrowWager() {
        ItemStack stack = wager.getItem(0);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        this.escrow = stack.copy();
        suppressSlotCallback = true;
        try {
            wager.setItem(0, ItemStack.EMPTY);
        } finally {
            suppressSlotCallback = false;
        }
        host.markDirty();
        return this.escrow;
    }

    /**
     * Escrows part of the slot and leaves the remainder where it was.
     *
     * <p>For the tables that take only part of a stack: the Vault (the useful part of an offering)
     * and the slot machine (up to its stake ceiling). The remainder stays in the slot, sealed like
     * the slot itself until the wager settles, and goes back with the seat. Like
     * {@link #escrowWager} it is synchronous and unconditional once started, so the two halves of the
     * split never both exist.
     */
    protected ItemStack escrowPartial(int count) {
        ItemStack stack = wager.getItem(0);
        if (stack.isEmpty() || count <= 0) return ItemStack.EMPTY;
        if (count >= stack.getCount()) return escrowWager();

        ItemStack taken = stack.copy();
        taken.setCount(count);
        this.escrow = taken;
        suppressSlotCallback = true;
        try {
            ItemStack remainder = stack.copy();
            remainder.setCount(stack.getCount() - count);
            wager.setItem(0, remainder);
        } finally {
            suppressSlotCallback = false;
        }
        host.markDirty();
        return this.escrow;
    }

    protected void refundEscrow() {
        if (escrow.isEmpty()) return;
        suppressSlotCallback = true;
        try {
            if (wager.getItem(0).isEmpty()) {
                wager.setItem(0, escrow.copy());
            } else {
                payout.add(escrow.copy());
            }
        } finally {
            suppressSlotCallback = false;
        }
        escrow = ItemStack.EMPTY;
        host.markDirty();
    }

    protected void setPayout(List<ItemStack> stacks) {
        payout.clear();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) payout.add(stack.copy());
        }
        host.markDirty();
    }

    /** Inventory first, then back into the buffer, then the world — never silently nowhere. */
    public void deliverPayout(ServerPlayer player) {
        if (payout.isEmpty() || !canClaim(player)) return;
        List<ItemStack> leftovers = new ArrayList<>(2);
        for (ItemStack stack : payout) {
            ItemStack copy = stack.copy();
            // Vanilla's add() mutates the stack down to whatever it could not place and answers
            // "did I place ANY of it". Trusting that boolean threw away the remainder of every
            // partial add -- which is exactly how a 128-item win arrived as a single stack of 64
            // when only one inventory slot was free. The stack itself is the only honest report.
            player.getInventory().add(copy);
            if (!copy.isEmpty()) leftovers.add(copy);
        }
        payout.clear();
        payout.addAll(leftovers);
        if (payout.isEmpty() && state == GameState.PAYOUT_PENDING) {
            setState(GameState.IDLE);
            wagerOwner = null;
        }
        host.markDirty();
        // No broadcast: the result was announced when the wager settled, and announcing it again
        // here put a second win banner on screen for a single win.
    }

    protected void broadcastPayout() {
        // A summary, not the stacks: see PayoutResolver.summarise. Cards are left out: what a chip
        // win paid travels as a number, and the card itself is already back in its slot.
        List<ItemStack> summary = new ArrayList<>(PayoutResolver.summarise(bannerStacks()));
        summary.removeIf(ChipCards::isCard);
        int seat = winnerSeat();
        boolean returned = !payout.isEmpty() || chipPayoutCents > 0;
        byte tier = seat >= 0 ? winTier() : returned ? S2CPayoutReady.TIER_RETURN : S2CPayoutReady.TIER_NONE;
        long cents = seat < 0 ? 0L : winCents();
        host.broadcast(id -> new S2CPayoutReady(id, sessionId, summary, (byte) seat, tier, cents));
        handOverSettledPayout();
    }

    /**
     * Hands a settled payout straight to its owner: into the inventory, and whatever does not fit
     * into the casino mailbox. A win that waits behind a Collect button is a second click on a
     * button that, once the payout is gone, turns into the button that starts the next game.
     *
     * <p>Only when there is an owner to name. A payout parked by a restart or an abandoned table
     * keeps waiting in the buffer, to be collected or swept to the mailbox as before.
     */
    protected void handOverSettledPayout() {
        if (payout.isEmpty() || state != GameState.PAYOUT_PENDING) return;
        UUID owner = payoutOwner();
        if (owner == null) return;
        net.minecraft.server.MinecraftServer server;
        try {
            server = host.hostLevel().getServer();
        } catch (RuntimeException e) {
            return;
        }
        if (server == null) return;
        List<ItemStack> stacks = List.copyOf(payout);
        if (!com.itemcasino.player.CasinoMailbox.send(server, owner, stacks)) return;
        payout.clear();
        setState(GameState.IDLE);
        wagerOwner = null;
        host.markDirty();
        if (state.acceptsItems()) onWagerChanged();
    }

    /** The items the result screen shows. The payout buffer, except where a game pays elsewhere. */
    protected List<ItemStack> bannerStacks() {
        return payout;
    }

    /** Whose win this was, or -1 when nothing was won. Solo tables only ever have seat 0. */
    protected int winnerSeat() {
        return chipWin || decidedWin ? 0 : -1;
    }

    /** Chips a win credited, stake included; zero for a win paid in items. */
    protected long winCents() {
        return chipWin ? chipPayoutCents : 0L;
    }

    /** How loudly a win is celebrated. Ten times the stake is a big win. */
    protected byte winTier() {
        if (chipWin) {
            return chipPayoutCents >= 10 * Math.max(1, committedStakeCents())
                    ? S2CPayoutReady.TIER_BIG : S2CPayoutReady.TIER_WIN;
        }
        // Items: the odds the table froze at commit, where there were any. One in ten or longer.
        return frozenPpm > 0 && frozenPpm <= 100_000 ? S2CPayoutReady.TIER_BIG : S2CPayoutReady.TIER_WIN;
    }

    /**
     * Everything this session is holding, handed to the player or, with nobody to name, the floor.
     * Used at teardown.
     */
    public void liquidate(@Nullable ServerPlayer to) {
        List<ItemStack> everything = new ArrayList<>(4);
        if (!wager.getItem(0).isEmpty()) everything.add(wager.removeItemNoUpdate(0));
        if (!escrow.isEmpty()) { everything.add(escrow.copy()); escrow = ItemStack.EMPTY; }
        everything.addAll(payout);
        payout.clear();

        giveTo(to, everything);
        liquidateExtraSeats();
        state = GameState.IDLE;
        deadlineTick = 0;
        wagerOwner = null;
        host.markDirty();
    }

    /**
     * Hands every other chair back what it put in, to <em>its own</em> owner.
     *
     * <p>Not to the player passed to {@link #liquidate}: that is whoever broke the table or closed
     * the last screen, and paying three players' stakes to one of them is theft dressed up as
     * cleanup. Offline owners are covered because the mailbox takes a UUID, not a player.
     */
    protected void liquidateExtraSeats() {
        for (int index = 1; index < seats(); index++) {
            Seat chair = seat(index);
            if (chair == null || !chair.holdsAnything()) continue;
            List<ItemStack> mine = new ArrayList<>(3);
            if (!chair.stack().isEmpty()) mine.add(chair.slot().removeItemNoUpdate(0));
            if (!chair.escrow().isEmpty()) mine.add(chair.escrow().copy());
            mine.addAll(chair.takePayout());
            chair.clearWager();
            giveTo(ownerOfSeat(index), mine);
        }
    }

    /**
     * Hands stacks to a player through the casino mailbox: into their inventory when they can hold
     * on to it, into the mailbox when they cannot, and never into the inventory of a player who is
     * dead or already disconnected.
     *
     * <p>That last case is why this exists. A pocket game is torn down when its menu
     * closes, and a menu closes when a dead player respawns (the old player entity is removed, its
     * inventory already dropped and never copied to the new one) and when a player disconnects (after
     * {@code PlayerList.remove} has saved them). Adding to the inventory there destroyed whatever was
     * in the slot, which after a chip game is the whole Chip Card. With nobody to name, the floor.
     */
    protected void giveTo(@Nullable ServerPlayer to, List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        net.minecraft.server.MinecraftServer server = to == null ? null : to.level().getServer();
        if (server != null && com.itemcasino.player.CasinoMailbox.send(server, to.getUUID(), stacks)) return;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) host.dropOverflow(stack.copy());
        }
    }

    /**
     * The same, to an owner who may be nowhere near the table — or nowhere in the world. The
     * mailbox is addressed by UUID, so a chair whose player logged out mid-hand is still paid.
     */
    protected void giveTo(@Nullable UUID owner, List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        net.minecraft.server.MinecraftServer server = null;
        if (owner != null) {
            try {
                ServerLevel level = host.hostLevel();
                server = level == null ? null : level.getServer();
            } catch (RuntimeException ignored) {
                server = null;
            }
        }
        if (server != null && com.itemcasino.player.CasinoMailbox.send(server, owner, stacks)) return;
        boolean noWorld;
        try {
            noWorld = host.hostLevel() == null;
        } catch (RuntimeException e) {
            noWorld = true;
        }
        if (noWorld) {
            // Nothing can take them: no mailbox without a server, no floor without a world. This is
            // what lost the other chairs' winnings when a shared hand was restored (a block entity
            // loads before it has a level); every caller now waits for a tick instead. Should one
            // ever get here again, it is said out loud rather than swallowed.
            ItemCasino.LOGGER.error("Casino session {}: could not hand {} to {} -- no world yet",
                    sessionId, stacks, owner);
            return;
        }
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) host.dropOverflow(stack.copy());
        }
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        if (restoredBookkeeping != null) runRestoredBookkeeping();
        if (state == GameState.IDLE && escrow.isEmpty() && payout.isEmpty()) return;
        long now = host.hostLevel().getGameTime();

        // The client's animation acknowledgement is an optimisation, never a precondition. If it
        // never arrives -- lag, a crash, or a client hoping to stall -- the deadline settles.
        if (state == GameState.ROLLING && deadlineTick > 0 && now >= deadlineTick) {
            onDeadline();
        }

        long abandonTicks = CasinoConfig.SERVER.abandonSeconds.get() * 20L;
        boolean stale = lastTouchTick > 0 && now - lastTouchTick > abandonTicks;
        // "Not IDLE" is not a sufficient trigger: an escrow stranded by a bug elsewhere would sit
        // in an IDLE session forever. Anything still holding a wager gets swept. A parked payout is
        // left alone: there is nothing to abandon about it, and re-touching it here every five
        // minutes both spammed the log and kept the table's absent-seat sweep from ever seeing it
        // as stale, so an uncollected win could never reach its owner's mailbox.
        if (stale && ((state != GameState.IDLE && state != GameState.PAYOUT_PENDING)
                || !escrow.isEmpty())) {
            abandon();
        }
    }

    protected void abandon() {
        ItemCasino.LOGGER.info("Abandoning casino session {}", sessionId);
        if (state.holdsEscrow()) forceSettle();
        refundEscrow();
        if (payout.isEmpty()) {
            setState(GameState.IDLE);
        } else if (state != GameState.PAYOUT_PENDING) {
            setState(GameState.ABORTED);
            setState(GameState.PAYOUT_PENDING);
        }
        deadlineTick = 0;
        lastTouchTick = host.hostLevel().getGameTime();
        host.markDirty();
    }

    // ------------------------------------------------------------------ the shared pot

    /**
     * Banks whatever the player did not get back.
     *
     * <p>Called by every house game from its own settle, after the payout has been worked out and
     * before the escrow is released. Measuring the loss as "staked minus what came back of the same
     * item" is what lets one method serve four games with four different payout shapes: the
     * Upgrader pays in a different item entirely, so all of it is lost; a dice roll pays back its
     * multiple or nothing; a surrendered blackjack hand pays back half, and half is what is banked.
     */
    protected void bankLoss(long stakedCount) {
        if (escrow.isEmpty() || stakedCount <= 0) return;
        long paidBack = 0;
        for (ItemStack stack : payout) {
            if (ItemStack.isSameItemSameComponents(stack, escrow)) paidBack += stack.getCount();
        }
        long lost = stakedCount - paidBack;
        if (lost > 0) Jackpot.bank(host.hostLevel(), escrow, lost);
    }

    /** Banks whatever a wager did not pay back: chips into the pot's chips, items into its hoard. */
    protected void bankLoss() {
        if (stakeIsChips()) {
            long lost = committedStakeCents() - chipPayoutCents;
            if (lost > 0) Jackpot.bankChips(host.hostLevel(), lost);
            return;
        }
        bankLoss(escrow.getCount());
    }

    /**
     * The end of a house wager, shared by the tables whose settle has no special shape (the
     * Upgrader, the dice, the slot machine and the mine field). One sequence, so a table added later
     * cannot forget to bank the loss or to hand the card back.
     *
     * @param log writes the table's own settlement line; only called when settlements are logged
     */
    protected final void settleHouseWager(Runnable log) {
        if (!setState(GameState.SETTLING)) return;
        materialiseDecidedOutcome();
        recordOutcome(stakedValue(), returnedValue());
        bankLoss();
        escrow = ItemStack.EMPTY;
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        returnCardsToSlots();
        rearmIfStakeLeft();
        touch();
        if (CasinoConfig.SERVER.logSettlements.get()) log.run();
        broadcastPayout();
    }

    /**
     * A table that took only part of a stack still has the rest in its slot once the wager settles,
     * and nothing touched the slot to arm it again: without this the button stayed dead until the
     * player moved the stack.
     */
    protected void rearmIfStakeLeft() {
        if (state.acceptsItems() && !wager.getItem(0).isEmpty()) onWagerChanged();
    }

    /** How many items of the escrowed kind a wager staked. Blackjack adds a doubled bet. */
    protected long stakedItemCount() {
        return escrow.getCount();
    }

    /** Whether a settled loss at this table feeds the pot. Not for a duel, nor for the Vault's offering. */
    protected boolean banksLosses() {
        return true;
    }

    /** Resolves a session whose client never acknowledged. */
    public abstract void forceSettle();

    /** The deadline of a rolling session has passed. Settles it, unless a game has a better idea. */
    protected void onDeadline() {
        forceSettle();
    }

    /** Fills {@link #payout} from the already-decided outcome. */
    protected abstract void materialiseDecidedOutcome();

    /** Called when the wager slot changes while nothing is committed. */
    protected void onWagerChanged() {}

    /** Identifies the game to the client and to the save format. */
    public abstract byte gameType();

    // ------------------------------------------------------------------ persistence

    public void save(ValueOutput out) {
        out.putString("state", state.name());
        out.putLong("session_id", sessionId);
        out.putInt("frozen_ppm", frozenPpm);
        out.putBoolean("decided_win", decidedWin);
        out.putInt("spin_ticks", spinTicks);
        out.putLong("deadline", deadlineTick);
        out.putLong("last_touch", lastTouchTick);
        out.store("wager", ItemStack.OPTIONAL_CODEC, wager.getItem(0));
        out.store("escrow", ItemStack.OPTIONAL_CODEC, escrow);
        out.store("payout", ItemStack.CODEC.listOf(), List.copyOf(payout));
        out.putLong("bet_chips", betChips);
        out.putLong("stake_cents", stakeCents);
        out.putLong("chip_payout_cents", chipPayoutCents);
        if (wagerOwner != null) out.putString("wager_owner", wagerOwner.toString());
        for (int index = 1; index <= extra.length; index++) {
            extra[index - 1].save(out, "seat" + index + "_");
        }
    }

    public void load(ValueInput in) {
        state = GameState.byName(in.getStringOr("state", GameState.IDLE.name()), GameState.IDLE);
        sessionId = in.getLongOr("session_id", 0L);
        frozenPpm = in.getIntOr("frozen_ppm", 0);
        decidedWin = in.getBooleanOr("decided_win", false);
        spinTicks = in.getIntOr("spin_ticks", 0);
        deadlineTick = in.getLongOr("deadline", 0L);
        lastTouchTick = in.getLongOr("last_touch", 0L);
        suppressSlotCallback = true;
        try {
            wager.setItem(0, in.read("wager", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        } finally {
            suppressSlotCallback = false;
        }
        escrow = in.read("escrow", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        payout.clear();
        in.read("payout", ItemStack.CODEC.listOf()).ifPresent(payout::addAll);
        betChips = Math.max(1, in.getLongOr("bet_chips", 10L));
        stakeCents = Math.max(0, in.getLongOr("stake_cents", 0L));
        chipPayoutCents = Math.max(0, in.getLongOr("chip_payout_cents", 0L));
        wagerOwner = in.getString("wager_owner").map(CasinoSession::parseUuid).orElse(null);
        for (int index = 1; index <= extra.length; index++) {
            extra[index - 1].load(in, "seat" + index + "_");
        }
        repairAfterLoad();
    }

    /**
     * A session cannot resume an animation across a restart and must not be left holding an escrow.
     * Because the outcome was decided at lock time and saved with the rest, the repair is
     * deterministic: materialise what was already owed and park it for collection.
     */
    protected void repairAfterLoad() {
        if (!state.holdsEscrow()) return;
        ItemCasino.LOGGER.info("Repairing casino session {} restored in state {}", sessionId, state);
        materialiseDecidedOutcome();
        captureRestoredBookkeeping();
        escrow = ItemStack.EMPTY;
        deadlineTick = 0;
        state = SessionMachine.repairAfterLoad(state, !payout.isEmpty());
    }

    // ------------------------------------------------------------------ bookkeeping after a restore

    /**
     * What a session repaired on load still owes the pot and the stats.
     *
     * <p>A repair runs while the chunk is loading: the block entity has no level yet and the item
     * values may not exist yet, so it can neither bank a loss nor price a stake. It writes down what
     * it settled, and the first tick that has both finishes the job. Not persisted: if the chunk
     * unloads again before it ever ticks, the loss simply is not banked, which is what happened to
     * every restored loss before this existed.
     */
    @Nullable protected transient java.util.function.Consumer<ServerLevel> restoredBookkeeping;

    /** The solo house games' version: one owner, one stake, a loss banked when the table banks losses. */
    protected void captureRestoredBookkeeping() {
        if (escrow.isEmpty()) return;
        final UUID owner = wagerOwner;
        final boolean chips = stakeCents > 0 && ChipCards.isCard(escrow);
        final ItemStack prototype = escrow.copyWithCount(1);
        final long count = stakedItemCount();
        final long stakedCents = chips ? committedStakeCents() : 0L;
        final long paidCents = chips ? chipPayoutCents : 0L;
        final List<ItemStack> paid = new ArrayList<>();
        for (ItemStack stack : payout) if (!ChipCards.isCard(stack)) paid.add(stack.copy());
        final boolean bank = banksLosses();
        restoredBookkeeping = level -> {
            ValuationSnapshot values = ValuationEngine.snapshot();
            long unit = StackValuator.unitValue(prototype, values);
            long staked = chips ? Chips.valueOfCents(stakedCents)
                    : unit == Fixed.INF ? 0L : Fixed.mul(unit, count);
            long returned = Fixed.add(valueOf(paid), Chips.valueOfCents(paidCents));
            if (owner != null) CasinoStats.recordWager(level.getServer(), owner, gameType(), staked, returned);
            if (!bank) return;
            if (chips) {
                Jackpot.bankChips(level, stakedCents - paidCents);
            } else {
                long paidBack = 0;
                for (ItemStack stack : paid) {
                    if (ItemStack.isSameItemSameComponents(stack, prototype)) paidBack += stack.getCount();
                }
                Jackpot.bank(level, prototype, count - paidBack);
            }
        };
    }

    private void runRestoredBookkeeping() {
        if (!ValuationEngine.snapshot().isReady()) return;
        ServerLevel level;
        try {
            level = host.hostLevel();
        } catch (RuntimeException e) {
            return;
        }
        if (level == null) return;
        java.util.function.Consumer<ServerLevel> pending = restoredBookkeeping;
        restoredBookkeeping = null;
        try {
            pending.accept(level);
        } catch (RuntimeException e) {
            ItemCasino.LOGGER.error("Could not book a casino session restored from disk", e);
        }
    }

    @Nullable
    protected static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    protected ServerLevel serverLevel() {
        return host.hostLevel();
    }
}
