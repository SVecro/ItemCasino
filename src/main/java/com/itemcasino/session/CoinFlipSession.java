package com.itemcasino.session;

import com.itemcasino.chips.ChipCards;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.network.s2c.S2CCoinFlipResult;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
 * A duel: two players, two stakes of matching worth, one coin, winner takes both.
 *
 * <p>The house does not play. There is no edge here and no rake — the only reason the table exists
 * is that it holds both stakes while the coin is in the air, which is the part two players cannot
 * do for each other. Everything else in this class exists to make sure that trust is not misplaced:
 *
 * <ul>
 *   <li>Neither stake moves until <em>both</em> players have committed, so nobody is left having
 *       staked against an opponent who wandered off.</li>
 *   <li>The two stakes must be worth roughly the same, priced by the same engine that prices every
 *       other wager in the mod. A duel is not a place to trade a diamond for a dirt block.</li>
 *   <li>The winner is drawn once, on commit, and the payout is materialised then. Only the winner
 *       can collect it.</li>
 *   <li>If it is abandoned or the block is broken mid-duel, both stakes go back rather than to
 *       whoever happens to be standing there.</li>
 * </ul>
 */
public class CoinFlipSession extends CasinoSession {

    public static final int SEATS = 2;

    /** Seat B's stake. Seat A uses the base class's {@code wager}. */
    private final SimpleContainer wagerB = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            host.markDirty();
            if (!suppressB) onWagerChanged();
        }
    };

    private boolean suppressB;
    private final boolean[] ready = new boolean[SEATS];
    private ItemStack escrowB = ItemStack.EMPTY;
    /** Seat B's bet setting and committed chips; seat A's live in the base class. */
    private long betChipsB = 10;
    private long stakeCentsB;
    private int winnerSeat = -1;
    @Nullable private UUID winnerId;
    /** Who put up each stake, captured when the coin goes up. Stats and refunds follow these. */
    private final UUID[] stakeOwners = new UUID[SEATS];

    public CoinFlipSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_COIN_FLIP; }

    @Override
    public int seats() { return SEATS; }

    public SimpleContainer wagerContainerB() { return wagerB; }

    @Nullable
    @Override
    protected SimpleContainer slotContainer(int seat) {
        return seat == 1 ? wagerB : super.slotContainer(seat);
    }

    /**
     * A duellist who closes the screen withdraws their commitment. Their stake goes back to them
     * with their seat (the host does that), and the other side must press ready again against
     * whoever sits down next.
     */
    @Override
    public void onMenuClosed(Player player) {
        int seat = seatIndex(player);
        if (seat >= 0 && seat < SEATS && state.acceptsItems()) {
            ready[seat] = false;
            host.markDirty();
        }
        super.onMenuClosed(player);
    }

    public ItemStack wagerStackB() { return wagerB.getItem(0); }

    // ------------------------------------------------------------------ chips

    @Override
    public long betChips(int seat) { return seat == 1 ? betChipsB : betChips; }

    @Override
    protected void storeBet(int seat, long chips) {
        if (seat == 1) betChipsB = chips;
        else betChips = chips;
    }

    private ItemStack slotStack(int seat) {
        return seat == 1 ? wagerStackB() : wagerStack();
    }

    /** A duel is items against items or chips against chips: never a card against a pickaxe. */
    private boolean sameKind() {
        return ChipCards.isCard(wagerStack()) == ChipCards.isCard(wagerStackB());
    }

    /** What a seat's slot would stake: its chip bet, or its stack's worth. */
    private long slotValue(int seat, ValuationSnapshot snapshot) {
        ItemStack stack = slotStack(seat);
        if (ChipCards.isCard(stack)) {
            long chips = effectiveBetChips(stack, seat);
            return chips > 0 ? Chips.valueOfCents(Chips.centsOfChips(chips)) : 0;
        }
        return StackValuator.value(stack, snapshot);
    }

    private boolean stakeAcceptable(int seat, ValuationSnapshot snapshot) {
        ItemStack stack = slotStack(seat);
        if (ChipCards.isCard(stack)) return effectiveBetChips(stack, seat) >= 1;
        return StackValuator.reject(stack, snapshot) == null;
    }

    public boolean isReady(int seat) { return seat >= 0 && seat < SEATS && ready[seat]; }

    /** The seat that won the last flip, or -1; also who the result screen celebrates. */
    @Override
    public int winnerSeat() { return winnerSeat; }

    /** 1 when both stakes are on the table and worth about the same. Read by the screen. */
    @Override
    public int option() {
        return matched(ValuationEngine.snapshot()) ? 1 : 0;
    }

    // ------------------------------------------------------------------ arming

    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        // Changing your stake withdraws your commitment: otherwise a player could press ready on a
        // diamond, swap it for a pebble and let the other side's commitment carry the duel.
        ready[0] = false;
        ready[1] = false;
        setState(readyToArm() ? GameState.ARMED : GameState.IDLE);
    }

    private boolean readyToArm() {
        if (wagerStack().isEmpty() || wagerStackB().isEmpty()) return false;
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        return sameKind() && stakeAcceptable(0, snapshot) && stakeAcceptable(1, snapshot)
                && matched(snapshot);
    }

    /**
     * Are the two stakes close enough in worth to be a fair coin?
     *
     * <p>"Close enough" rather than "equal" because exact equality is unreachable with whole items:
     * five iron ingots against a block of iron is a real duel, and refusing it would make the table
     * useless for anything but identical stacks.
     */
    private boolean matched(ValuationSnapshot snapshot) {
        long a = slotValue(0, snapshot);
        long b = slotValue(1, snapshot);
        if (a <= 0 || b <= 0 || a == Fixed.INF || b == Fixed.INF) return false;
        long high = Math.max(a, b);
        long low = Math.min(a, b);
        double tolerance = CasinoConfig.SERVER.coinFlipTolerancePpm.get() / 1_000_000.0;
        // Floating point is fine here and only here: this is a yes/no on whether the duel is fair
        // enough to run, not a number anybody is paid from.
        return (double) low / (double) high >= 1.0 - tolerance;
    }

    /**
     * A seat's stake, escrowed or not.
     *
     * <p>Falling back to the escrow matters: once both players commit, the slots empty and the
     * coin goes up, and that is precisely when both of them want to see what is in the pot.
     */
    public long stakeValue(int seat) {
        if (state.acceptsItems()) {
            ItemStack stack = slotStack(seat);
            return stack.isEmpty() ? -1L : slotValue(seat, snapshot());
        }
        long cents = seat == 1 ? stakeCentsB : stakeCents;
        if (cents > 0) return Chips.valueOfCents(cents);
        ItemStack stack = stakeOf(seat);
        return stack.isEmpty() ? -1L : StackValuator.value(stack, snapshot());
    }

    private ItemStack stakeOf(int seat) {
        if (seat == 0) return wagerStack().isEmpty() ? escrow : wagerStack();
        return wagerStackB().isEmpty() ? escrowB : wagerStackB();
    }

    @Override
    public int stakeMilli(int seat) {
        long value = stakeValue(seat);
        if (value < 0 || value == Fixed.INF) return -1;
        return (int) Math.min(Integer.MAX_VALUE, value / 1000L);
    }

    // ------------------------------------------------------------------ committing

    /**
     * One player says they are in. The coin only leaves the table once both have.
     *
     * <p>Reusing the wager button for this is deliberate: "commit" is the same gesture in every
     * game, and the only difference here is that it takes two of them.
     */
    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }
        int seat = host.seatIndex(player);
        if (seat < 0 || seat >= SEATS) return false;
        if (!sameKind()) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.duel_mixed"), true);
            return false;
        }
        if (!readyToArm()) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.unmatched"), true);
            return false;
        }

        ready[seat] = true;
        touch();
        host.markDirty();
        if (!ready[0] || !ready[1]) {
            // Still waiting on the other side. Everyone watching sees the state slot change.
            return true;
        }
        return start();
    }

    private boolean start() {
        this.frozenSnapshot = ValuationEngine.snapshot();
        this.sessionId++;
        escrowBoth();
        if (!setState(GameState.LOCKED)) return false;

        // The coin is weighted by the stakes, so each player expects back exactly what they put up
        // however far apart the tolerance lets the two stakes be. An even coin gave the smaller
        // stake up to 5.6 % more than it staked.
        boolean chips = stakeCents > 0 && stakeCentsB > 0;
        long valueA = chips ? Chips.valueOfCents(stakeCents) : valueOf(escrow);
        long valueB = chips ? Chips.valueOfCents(stakeCentsB) : valueOf(escrowB);
        int chanceA = com.itemcasino.core.game.DuelOdds.seatAChancePpm(valueA, valueB);
        RandomSource rng = host.random();
        this.winnerSeat = com.itemcasino.core.game.DuelOdds.winner(
                rng.nextInt(com.itemcasino.core.game.DuelOdds.PPM), chanceA);
        this.decidedWin = winnerSeat == 0;
        this.stakeOwners[0] = host.seatId(0);
        this.stakeOwners[1] = host.seatId(1);
        // From the seat, never from who happens to have the screen open: a duellist who readied and
        // closed the screen is still the owner of their chair, and used to lose the pot for it.
        this.winnerId = stakeOwners[winnerSeat];
        this.wagerOwner = null;
        this.frozenPpm = chanceA;                       // seat A's chance; seat B's is the rest
        this.spinTicks = CasinoConfig.SERVER.coinFlipTicks.get();
        setState(GameState.ROLLING);
        this.deadlineTick = host.hostLevel().getGameTime() + spinTicks + 40L;
        armAcknowledgement(spinTicks);
        touch();

        int flourish = rng.nextInt(Integer.MAX_VALUE);
        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), frozenPpm));
        host.broadcast(id -> new S2CCoinFlipResult(id, sessionId, (byte) winnerSeat, flourish,
                spinTicks));

        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.AUDIT.info("[wager] coin_flip session={} a={} b={} chanceA={}ppm winner=seat{}",
                    sessionId, escrow, escrowB, chanceA, winnerSeat);
        }
        return true;
    }

    /** Both stakes move in the same call: there is no tick where one is held and the other is not. */
    private void escrowBoth() {
        long centsB = ChipCards.isCard(wagerStackB())
                ? Chips.centsOfChips(effectiveBetChips(wagerStackB(), 1)) : 0L;
        escrowStake();
        stakeCentsB = centsB;
        suppressB = true;
        try {
            escrowB = wagerB.getItem(0).copy();
            wagerB.setItem(0, ItemStack.EMPTY);
        } finally {
            suppressB = false;
        }
        host.markDirty();
    }

    // ------------------------------------------------------------------ settling

    /** Both chip stakes, for the winner's result screen; read in the same call as the settle. */
    private long settledPotCents;

    @Override
    protected long winCents() {
        return settledPotCents;
    }

    @Override
    protected byte winTier() {
        return com.itemcasino.network.s2c.S2CPayoutReady.TIER_WIN;
    }

    @Override
    public boolean commitWager(ServerPlayer player) { return placeWager(player); }

    @Override
    public boolean acknowledge(long claimedSession) { return finishFlip(claimedSession); }

    /** A duel feeds nothing to the pot: two players trade stakes and the house loses nothing. */
    @Override
    protected boolean banksLosses() { return false; }

    /**
     * A duel saved mid-flip. The base repair pays the pot out of both escrows but only empties seat
     * A's: left set, seat B's stake was handed back a second time when the table was later broken,
     * the same collateral bug the blackjack table once had with its double. And the stats, which the
     * base class books for one owner, are booked for both chairs.
     */
    @Override
    protected void repairAfterLoad() {
        boolean holding = state.holdsEscrow();
        java.util.function.Consumer<net.minecraft.server.level.ServerLevel> duelBooking =
                holding ? captureDuelBookkeeping() : null;
        super.repairAfterLoad();
        if (!state.holdsEscrow()) {
            escrowB = ItemStack.EMPTY;
            stakeCentsB = 0;
            ready[0] = false;
            ready[1] = false;
        }
        if (duelBooking != null) restoredBookkeeping = duelBooking;
    }

    private java.util.function.Consumer<net.minecraft.server.level.ServerLevel> captureDuelBookkeeping() {
        final boolean chips = stakeCents > 0 && stakeCentsB > 0;
        final long centsA = stakeCents;
        final long centsB = stakeCentsB;
        final ItemStack stakeA = escrow.copy();
        final ItemStack stakeB = escrowB.copy();
        final UUID ownerA = stakeOwners[0];
        final UUID ownerB = stakeOwners[1];
        final int winner = winnerSeat;
        return level -> {
            long a = chips ? Chips.valueOfCents(centsA) : valueOf(stakeA);
            long b = chips ? Chips.valueOfCents(centsB) : valueOf(stakeB);
            long pot = Fixed.add(a, b);
            recordOutcome(ownerA, a, winner == 0 ? pot : 0L);
            recordOutcome(ownerB, b, winner == 1 ? pot : 0L);
        };
    }

    public boolean finishFlip(long claimedSession) {

        if (state != GameState.ROLLING) return false;
        if (claimedSession != 0 && claimedSession != sessionId) return false;
        settle();
        return true;
    }

    @Override
    public void forceSettle() {
        if (state == GameState.ROLLING) settle();
    }

    private void settle() {
        if (!setState(GameState.SETTLING)) return;
        boolean chips = stakeCents > 0 && stakeCentsB > 0;
        this.settledPotCents = chips ? Chips.add(stakeCents, stakeCentsB) : 0L;
        long stakeA = chips ? Chips.valueOfCents(stakeCents) : valueOf(escrow);
        long stakeB = chips ? Chips.valueOfCents(stakeCentsB) : valueOf(escrowB);
        materialiseDecidedOutcome();
        long pot = Fixed.add(stakeA, stakeB);
        recordOutcome(stakeOwners[0], stakeA, winnerSeat == 0 ? pot : 0L);
        recordOutcome(stakeOwners[1], stakeB, winnerSeat == 1 ? pot : 0L);
        escrow = ItemStack.EMPTY;
        escrowB = ItemStack.EMPTY;
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        // A chip duel put both cards straight back in their chairs: the table is ready to re-arm.
        if (state.acceptsItems()) onWagerChanged();
        touch();
        broadcastPayout();
    }

    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        if (escrow.isEmpty() && escrowB.isEmpty()) return;
        if (stakeCents > 0 && stakeCentsB > 0 && ChipCards.isCard(escrow) && ChipCards.isCard(escrowB)) {
            settleChips();
            return;
        }
        List<ItemStack> pot = new ArrayList<>(4);
        PayoutResolver.addSplit(pot, escrow, escrow.getCount());
        PayoutResolver.addSplit(pot, escrowB, escrowB.getCount());
        setPayout(pot);
    }

    /**
     * A chip duel moves chips, not cards: the loser's stake is taken off their card and put on the
     * winner's, and each card goes back to the chair it was bet from. Nothing waits to be claimed,
     * and nobody ever holds the other player's card.
     */
    private void settleChips() {
        ItemStack cardA = escrow.copy();
        ItemStack cardB = escrowB.copy();
        long balanceA = ChipCards.balance(cardA);
        long balanceB = ChipCards.balance(cardB);
        if (winnerSeat == 0) {
            ChipCards.setBalance(cardA, Chips.add(balanceA, stakeCentsB));
            ChipCards.setBalance(cardB, Math.max(0, balanceB - stakeCentsB));
        } else {
            ChipCards.setBalance(cardA, Math.max(0, balanceA - stakeCents));
            ChipCards.setBalance(cardB, Chips.add(balanceB, stakeCents));
        }
        giveBack(0, cardA);
        giveBack(1, cardB);
        escrow = ItemStack.EMPTY;
        escrowB = ItemStack.EMPTY;
        stakeCents = 0;
        stakeCentsB = 0;
        host.markDirty();
    }

    /** Into the seat's own slot when it is free, otherwise to the seat's owner. */
    private void giveBack(int seat, ItemStack card) {
        SimpleContainer container = slotContainer(seat);
        if (container != null && container.getItem(0).isEmpty()) {
            if (seat == 1) {
                suppressB = true;
                try {
                    wagerB.setItem(0, card);
                } finally {
                    suppressB = false;
                }
            } else {
                returnToSlotA(card);
            }
            return;
        }
        returnStake(seat, card);
    }

    /**
     * The pot belongs to the winner, by UUID. Anyone else clicking Collect is told no by the server,
     * and a duel whose winner cannot be named pays nobody rather than whoever asks first.
     */
    @Override
    public boolean canClaim(Player player) {
        UUID owner = payoutOwner();
        return owner != null && owner.equals(player.getUUID());
    }

    @Nullable
    @Override
    public UUID payoutOwner() {
        if (winnerId != null) return winnerId;
        return winnerSeat >= 0 && winnerSeat < SEATS ? host.seatId(winnerSeat) : null;
    }

    /**
     * A duel that never finished gives each stake back to the player who put it up. The base class
     * hands everything to one player, which is right for a solo game and would be theft here.
     */
    @Override
    public void liquidate(@Nullable ServerPlayer to) {
        if (state != GameState.PAYOUT_PENDING) {
            returnStake(0, wagerStack().isEmpty() ? escrow : wagerStack());
            returnStake(1, wagerStackB().isEmpty() ? escrowB : wagerStackB());
            suppressSlotCallbackBoth();
            escrow = ItemStack.EMPTY;
            escrowB = ItemStack.EMPTY;
        }
        ready[0] = false;
        ready[1] = false;
        super.liquidate(to);
        if (state == GameState.IDLE) {
            winnerSeat = -1;
            winnerId = null;
        }
    }

    private void suppressSlotCallbackBoth() {
        suppressB = true;
        try {
            wagerB.setItem(0, ItemStack.EMPTY);
        } finally {
            suppressB = false;
        }
        wager.removeItemNoUpdate(0);
    }

    /**
     * Back to the player who put the stake up — online or not, looking at the table or not — and
     * only onto the floor when nobody can be named.
     */
    private void returnStake(int seat, ItemStack stake) {
        if (stake.isEmpty()) return;
        UUID owner = stakeOwners[seat] != null ? stakeOwners[seat] : host.seatId(seat);
        boolean sent = false;
        try {
            sent = com.itemcasino.player.CasinoMailbox.send(host.hostLevel().getServer(), owner,
                    stake.copy());
        } catch (RuntimeException e) {
            ItemCasino.LOGGER.error("Could not return a duel stake to {}", owner, e);
        }
        if (!sent) host.dropOverflow(stake.copy());
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.store("wager_b", ItemStack.OPTIONAL_CODEC, wagerB.getItem(0));
        out.store("escrow_b", ItemStack.OPTIONAL_CODEC, escrowB);
        out.putLong("bet_chips_b", betChipsB);
        out.putLong("stake_cents_b", stakeCentsB);
        out.putBoolean("ready_a", ready[0]);
        out.putBoolean("ready_b", ready[1]);
        out.putInt("winner_seat", winnerSeat);
        if (winnerId != null) out.putString("winner_id", winnerId.toString());
        if (stakeOwners[0] != null) out.putString("stake_owner_a", stakeOwners[0].toString());
        if (stakeOwners[1] != null) out.putString("stake_owner_b", stakeOwners[1].toString());
    }

    @Override
    public void load(ValueInput in) {
        suppressB = true;
        try {
            wagerB.setItem(0, in.read("wager_b", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        } finally {
            suppressB = false;
        }
        escrowB = in.read("escrow_b", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        betChipsB = Math.max(1, in.getLongOr("bet_chips_b", 10L));
        stakeCentsB = Math.max(0, in.getLongOr("stake_cents_b", 0L));
        ready[0] = in.getBooleanOr("ready_a", false);
        ready[1] = in.getBooleanOr("ready_b", false);
        winnerSeat = in.getIntOr("winner_seat", -1);
        winnerId = in.getString("winner_id").map(CasinoSession::parseUuid).orElse(null);
        stakeOwners[0] = in.getString("stake_owner_a").map(CasinoSession::parseUuid).orElse(null);
        stakeOwners[1] = in.getString("stake_owner_b").map(CasinoSession::parseUuid).orElse(null);
        // Last, because the base class finishes by repairing a session restored mid-flip, and that
        // repair pays out the pot -- which has to be loaded before it can be paid.
        super.load(in);
    }
}
