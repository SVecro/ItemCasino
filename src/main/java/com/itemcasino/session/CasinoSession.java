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

    protected CasinoSession(SessionHost host) {
        this.host = host;
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

    /** How many players this game seats. One for the solo games, two for a duel. */
    public int seats() { return 1; }

    /** Which seat this player holds, or -1. Only a duel has more than one. */
    public int seatIndex(@Nullable Player player) { return host.seatIndex(player); }

    /**
     * A per-game setting the seated player chooses before committing: the dice bet, the
     * upgrader's count, the Vault's share of the pot, the number of mines. The server only accepts it
     * while the game still takes items, and freezes whatever it affects at commit.
     */
    public int option() { return 0; }

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
        return state.holdsEscrow() || !escrow.isEmpty() || !payout.isEmpty();
    }

    /** A live wager keeps the odds it was locked against, whatever {@code /reload} does after. */
    public ValuationSnapshot snapshot() {
        return frozenSnapshot != null ? frozenSnapshot : ValuationEngine.snapshot();
    }

    /** Routed from the menu so the host can free a seat or tear a pocket session down. */
    public void onMenuClosed(Player player) {
        host.onViewerClosed(player);
    }

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

    /** The bet setting for a seat. One for every table but the duel. */
    public long betChips(int seat) { return betChips; }

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
        betChips = chips;
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

    /** The slot a seat stakes from. One for every solo game; a duel overrides it for seat B. */
    @Nullable
    protected SimpleContainer slotContainer(int seat) {
        return seat == 0 ? wager : null;
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
        String violation = SessionMachine.violation(state, !escrow.isEmpty(), !payout.isEmpty(),
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
     * <p>Only the Vault needs this: every other game takes the whole wager or none of it, and
     * giving them a partial path would be an invitation to leave a few items stranded in a slot the
     * state machine believes is empty. Like {@link #escrowWager} it is synchronous and unconditional
     * once started, so the two halves of the split never both exist.
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

    /** Everything this session is holding, handed to the player or the floor. Used at teardown. */
    public void liquidate(@Nullable ServerPlayer to) {
        List<ItemStack> everything = new ArrayList<>(4);
        if (!wager.getItem(0).isEmpty()) everything.add(wager.removeItemNoUpdate(0));
        if (!escrow.isEmpty()) { everything.add(escrow.copy()); escrow = ItemStack.EMPTY; }
        everything.addAll(payout);
        payout.clear();

        for (ItemStack stack : everything) {
            ItemStack copy = stack.copy();
            if (to != null) to.getInventory().add(copy);   // mutates copy to the leftover
            if (!copy.isEmpty()) host.dropOverflow(copy);
        }
        state = GameState.IDLE;
        deadlineTick = 0;
        wagerOwner = null;
        host.markDirty();
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
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
     * The moment a wager is committed, and with it the one chance in fifty thousand.
     *
     * <p>Rolled here rather than per game so no table can be added later that quietly forgets to
     * offer it. Duels do not call this: two players trading stakes never lose anything to the
     * house, so a duel would be a free roll, and two accounts passing the same stack back and forth
     * would be a jackpot farm.
     */
    protected void onWagerCommitted(ServerPlayer player) {
        if (!CasinoConfig.SERVER.jackpotEnabled.get() || escrow.isEmpty()) return;
        long value = stakeIsChips() ? Chips.valueOfCents(committedStakeCents())
                : StackValuator.value(escrow, snapshot());
        if (value == Fixed.INF || value <= 0) return;
        Jackpot.of(host.hostLevel()).rollAmbient(player, value);
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
        escrow = ItemStack.EMPTY;
        deadlineTick = 0;
        state = SessionMachine.repairAfterLoad(state, !payout.isEmpty());
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
