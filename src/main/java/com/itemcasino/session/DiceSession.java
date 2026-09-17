package com.itemcasino.session;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.core.game.Dice;
import com.itemcasino.core.game.GameState;
import com.itemcasino.network.s2c.S2CDiceResult;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Predict the Dice: name a chance and a direction, and the server rolls 0.00–99.99.
 *
 * <p>The rules are in {@link Dice}; this class is the table around them. The roll happens once, at
 * commit, and is saved with the bet, so a restart pays exactly what was rolled. The chance and the
 * direction are a table setting the seated player changes before committing, like the Vault's share.
 *
 * <p>This table replaced Double or Nothing and inherits its block, its save data and its place in
 * the stats. A Double or Nothing flip that was still in the air when the world was updated has no
 * roll to replay, so it is refunded.
 */
public class DiceSession extends CasinoSession {

    public static final int READOUT_LAST_ROLL = 0;
    public static final int READOUT_LAST_FLAGS = 1;
    public static final int READOUT_EDGE_PPM = 2;
    public static final int READOUT_LIMITS = 3;

    public static final int FLAG_WIN = 1;
    public static final int FLAG_OVER = 2;

    /** The bet the table is set to: chance in hundredths of a percent, and the direction. */
    private int chance = 4850;
    private boolean over;

    // The committed roll, frozen with it.
    private int betChance;
    private boolean betOver;
    private long betMultiplierPpm;
    /** The last roll, or -1 before the first. Kept on show after settling. */
    private int roll = -1;
    /** Loaded from a Double or Nothing save that was mid-flip: nothing to replay, so refund. */
    private transient boolean legacyInFlight;

    public DiceSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_DICE; }

    // ------------------------------------------------------------------ the bet

    private static int minChance() {
        return Math.min(CasinoConfig.SERVER.diceMinChance.get(), CasinoConfig.SERVER.diceMaxChance.get());
    }

    private static int maxChance() {
        return Math.max(CasinoConfig.SERVER.diceMinChance.get(), CasinoConfig.SERVER.diceMaxChance.get());
    }

    private static int edgePpm() {
        return CasinoConfig.SERVER.diceEdgePpm.get();
    }

    public int chance() { return Dice.clampChance(chance, minChance(), maxChance()); }

    public boolean over() { return over; }

    /** The bet as the menu carries it: see {@link Dice#pack}. */
    @Override
    public int option() {
        return Dice.pack(chance(), over);
    }

    /** Refused once rolled: a bet you could change after the dice left the cup is not a bet. */
    @Override
    public void setOption(ServerPlayer player, int value) {
        if (!state.acceptsItems()) return;
        int wanted = Dice.clampChance(Dice.chanceOf(value), minChance(), maxChance());
        boolean wantedOver = Dice.isOver(value);
        if (wanted == chance && wantedOver == over) return;
        chance = wanted;
        over = wantedOver;
        host.markDirty();
    }

    /** The chance in ppm: the committed bet's once rolled, the table's setting before. */
    @Override
    public int frozenPpm() {
        return (state.acceptsItems() ? chance() : betChance) * 100;
    }

    @Override
    public int readout(int index) {
        return switch (index) {
            // The roll in play is the table's secret until it settles, like the mines on the board:
            // a spectator who opened the screen mid-roll would otherwise read the result off the
            // data channel before the counter got there.
            case READOUT_LAST_ROLL -> state.holdsEscrow() ? 0 : roll + 1;
            case READOUT_LAST_FLAGS -> state.holdsEscrow() ? 0 : (decidedWin ? FLAG_WIN : 0) | (betOver ? FLAG_OVER : 0);
            case READOUT_EDGE_PPM -> edgePpm();
            case READOUT_LIMITS -> minChance() | maxChance() << 16;
            default -> 0;
        };
    }

    public int lastRoll() { return roll; }

    /** Dice are played in chips only: the multipliers are rarely a whole number of items. */
    @Override
    public boolean chipsOnly() { return true; }

    /** The bet whose win would reach the payout ceiling at the chance currently set. */
    @Override
    public long maxBetChips() {
        long multiplier = Dice.multiplierPpm(chance(), edgePpm());
        return Math.max(1, Dice.maxStake(CasinoConfig.SERVER.diceMaxPayoutChips.get(), multiplier));
    }

    // ------------------------------------------------------------------ playing

    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        setState(wagerStack().isEmpty() ? GameState.IDLE : GameState.ARMED);
    }

    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
        // Defence in depth: the packet layer already refuses anyone not in the chair.
        if (!isSeated(player)) return false;
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!checkStake(player, snapshot)) return false;

        int bet = chance();
        long multiplier = Dice.multiplierPpm(bet, edgePpm());

        this.frozenSnapshot = snapshot;
        this.sessionId++;
        escrowStake();
        if (!setState(GameState.LOCKED)) return false;

        this.betChance = bet;
        this.betOver = over;
        this.betMultiplierPpm = multiplier;
        this.roll = host.random().nextInt(Dice.ROLLS);
        this.decidedWin = Dice.wins(roll, betChance, betOver);
        this.frozenPpm = betChance * 100;
        this.spinTicks = CasinoConfig.SERVER.diceRollTicks.get();
        setState(GameState.ROLLING);
        this.deadlineTick = host.hostLevel().getGameTime() + spinTicks + 40L;
        beginCommit(player, spinTicks);
        touch();

        int rolled = roll;
        boolean win = decidedWin;
        boolean dirOver = betOver;
        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), betChance * 100));
        host.broadcast(id -> new S2CDiceResult(id, sessionId, rolled, win, dirOver, bet, spinTicks));

        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.AUDIT.info("[wager] {} dice session={} stake={}c bet={} {} x{}ppm roll={} win={}",
                    player.getName().getString(), sessionId, stakeCents, betChance,
                    betOver ? "over" : "under", betMultiplierPpm, roll, decidedWin);
        }
        return true;
    }

    @Override
    public boolean commitWager(ServerPlayer player) { return placeWager(player); }

    @Override
    public boolean acknowledge(long claimedSession) { return finishRoll(claimedSession); }

    public boolean finishRoll(long claimedSession) {
        if (state != GameState.ROLLING) return false;
        if (claimedSession != 0 && claimedSession != sessionId) return false;
        settle();
        return true;
    }

    @Override
    public void forceSettle() {
        if (state != GameState.ROLLING) return;
        settle();
    }

    private void settle() {
        settleHouseWager(() -> ItemCasino.AUDIT.info("[settle] dice session={} roll={} win={} payout={}",
                sessionId, roll, decidedWin, payout));
    }

    /**
     * The win is re-derived from the saved roll and bet rather than from the saved flag, and the
     * multiplier from the saved chance, so an edited save still only pays what its own roll says —
     * at no better than the configured edge.
     */
    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        if (escrow.isEmpty()) return;
        if (legacyInFlight) {
            // A Double or Nothing flip saved mid-air by the old table: give the stake back.
            PayoutResolver.addSplit(payout, escrow, escrow.getCount());
            host.markDirty();
            return;
        }
        long multiplier = Math.min(betMultiplierPpm, Dice.multiplierPpm(betChance, 0));
        boolean won = Dice.wins(roll, betChance, betOver) && multiplier > 0;
        payChips(won ? Chips.payout(stakeCents, multiplier) : 0L);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.putInt("dice_chance", chance);
        out.putBoolean("dice_over", over);
        out.putInt("dice_bet_chance", betChance);
        out.putBoolean("dice_bet_over", betOver);
        out.putLong("dice_bet_multiplier_ppm", betMultiplierPpm);
        out.putInt("dice_roll", roll);
    }

    @Override
    public void load(ValueInput in) {
        // Before the base class, whose repair settles a roll restored mid-animation from these.
        chance = in.getIntOr("dice_chance", 4850);
        over = in.getBooleanOr("dice_over", false);
        betChance = Math.max(0, Math.min(Dice.ROLLS, in.getIntOr("dice_bet_chance", 0)));
        betOver = in.getBooleanOr("dice_bet_over", false);
        betMultiplierPpm = Math.max(0L, in.getLongOr("dice_bet_multiplier_ppm", 0L));
        roll = in.getIntOr("dice_roll", -1);
        legacyInFlight = in.getInt("dice_roll").isEmpty();
        super.load(in);
        legacyInFlight = false;
    }
}
