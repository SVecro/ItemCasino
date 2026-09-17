package com.itemcasino.session;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.wheel.WheelMath;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.core.value.Odds;
import com.itemcasino.network.s2c.S2COddsQuote;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.network.s2c.S2CWheelResult;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;

/** Sacrifice item A for a chance at item B. The roll happens at lock time, once. */
public class UpgraderSession extends CasinoSession {

    @Nullable private Item target;
    private float stopAngle;
    private int outputCount = 1;

    public UpgraderSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_UPGRADER; }

    @Nullable public Item target() { return target; }

    public float stopAngle() { return stopAngle; }

    public int outputCount() { return outputCount; }

    /** The number of target items asked for, which the client sets and the screen shows. */
    @Override
    public int option() { return outputCount; }

    /**
     * Lets the player ask for more than one of the target.
     *
     * <p>Why this exists: the odds are capped at 90%, so once a wager is worth more than the target
     * every extra point of value is simply thrown away — the wheel is already as generous as it
     * will ever be and the surplus buys nothing. Asking for several of the target spends that
     * surplus on the prize instead of on nothing, at exactly the same odds formula.
     *
     * <p>{@link #maxOutputCount} is where it stops: one step past the point where the odds leave
     * the cap. So a player can always convert all of their surplus, and then take one step beyond
     * into a real gamble, and no further.
     */
    @Override
    public void setOption(ServerPlayer player, int value) {
        if (state != GameState.IDLE && state != GameState.ARMED) return;
        outputCount = Math.max(1, Math.min(value, maxOutputCount()));
        onWagerChanged();
        sendQuote(player);
        host.markDirty();
    }

    /**
     * The largest number of target items this wager may ask for.
     *
     * <p>The odds are {@code 0.90 x input / (target x n)} clamped at 0.90, so they sit on the cap
     * for every {@code n} up to {@code input / target} — the whole range where the surplus is being
     * wasted. One beyond that is the first count that costs something, and that is the ceiling.
     */
    public int maxOutputCount() {
        if (target == null) return 1;
        ValuationSnapshot snapshot = snapshot();
        ItemStack stack = wagerStack();
        if (stack.isEmpty()) return 1;
        long input = inputValue(stack, snapshot);
        long unit = snapshot.value(target);
        if (input == Fixed.INF || unit == Fixed.INF || unit <= 0) return 1;
        long atCap = input / unit;
        return (int) Math.max(1, Math.min(MAX_OUTPUT, atCap + 1));
    }

    /** A hard ceiling regardless of the arithmetic: nobody needs four stacks out of one spin. */
    private static final int MAX_OUTPUT = 128;

    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        if (wagerStack().isEmpty()) {
            frozenPpm = 0;
            setState(GameState.IDLE);
        } else if (target != null) {
            // The ceiling moves with the wager, so a stack taken back out must not leave the
            // player holding a count they can no longer afford.
            outputCount = Math.max(1, Math.min(outputCount, maxOutputCount()));
            frozenPpm = quote(wagerStack(), target, ValuationEngine.snapshot());
            setState(frozenPpm == Odds.ILLEGAL ? GameState.IDLE : GameState.ARMED);
        }
        // Adding to or taking from the stack changes the odds, and the client is still holding the
        // number it was quoted when the target was picked. Without this the percentage on the
        // button stops matching the percentage the roll uses.
        ServerPlayer viewer = host.seatedPlayer();
        if (viewer != null && target != null) sendQuote(viewer);
    }

    public boolean selectTarget(ServerPlayer player, Identifier targetId) {
        if (state != GameState.IDLE && state != GameState.ARMED) return false;
        if (!isSeated(player)) return false;

        Item candidate = BuiltInRegistries.ITEM.getValue(targetId);
        if (candidate == null || candidate == Items.AIR) return false;
        if (!ValuationEngine.snapshot().isTargetable(candidate)) return false;

        this.target = candidate;
        this.outputCount = 1;
        touch();
        ItemStack stack = wagerStack();
        frozenPpm = stack.isEmpty() ? Odds.ILLEGAL
                : quote(stack, candidate, ValuationEngine.snapshot());
        setState(frozenPpm == Odds.ILLEGAL || stack.isEmpty() ? GameState.IDLE : GameState.ARMED);
        host.markDirty();
        sendQuote(player);
        return true;
    }

    public void clearTarget() {
        if (state != GameState.IDLE && state != GameState.ARMED) return;
        target = null;
        frozenPpm = 0;
        setState(GameState.IDLE);
    }

    /** Prices both sides live and tells the client exactly what it would be wagering. */
    private void sendQuote(ServerPlayer player) {
        if (target == null) return;
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        ItemStack stack = wagerStack();
        long inputValue = stack.isEmpty() ? Fixed.INF : inputValue(stack, snapshot);
        long unit = snapshot.value(target);
        // The quoted target value is the whole prize, so the screen's two numbers are the two sides
        // of the bet the player is actually taking rather than a per-item price they must multiply.
        long targetValue = unit == Fixed.INF ? Fixed.INF : saturatingMultiply(unit, outputCount);
        Identifier id = BuiltInRegistries.ITEM.getKey(target);
        if (id == null) return;
        com.itemcasino.network.CasinoNetwork.send(player, new S2COddsQuote(
                player.containerMenu.containerId, id, Odds.ppm(inputValue, targetValue),
                wire(inputValue), wire(targetValue)));
    }

    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED || target == null) return false;
        // Defence in depth: the packet layer already refuses anyone not in the chair, but a rule
        // that only holds when the caller remembered to check is not a rule.
        if (!isSeated(player)) return false;
        if (!CasinoConfig.SERVER.allowCreative.get() && player.getAbilities().instabuild) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.creative"), true);
            return false;
        }

        ItemStack stack = wagerStack();
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!checkStake(player, snapshot)) return false;
        int ppm = quote(stack, target, snapshot);
        if (ppm == Odds.ILLEGAL) {
            // Both sides are priced (rejection above covers the wager, isTargetable the target), so
            // an illegal quote here means the shot is longer than one in a thousand.
            player.displayClientMessage(Component.translatable("itemcasino.reject.long_shot"), true);
            return false;
        }

        this.frozenSnapshot = snapshot;
        this.frozenPpm = ppm;
        this.sessionId++;
        escrowStake();
        if (!setState(GameState.LOCKED)) return false;

        RandomSource rng = host.random();
        this.decidedWin = rng.nextInt(Odds.PPM) < ppm;                 // the only roll
        this.stopAngle = WheelMath.stopAngle(decidedWin, ppm, rng.nextFloat());
        this.spinTicks = CasinoConfig.SERVER.wheelSpinTicks.get();
        setState(GameState.ROLLING);
        this.deadlineTick = host.hostLevel().getGameTime() + spinTicks + 40L;
        beginCommit(player, spinTicks);
        touch();

        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), frozenPpm));
        host.broadcast(id -> new S2CWheelResult(id, sessionId, decidedWin, stopAngle, spinTicks));

        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.AUDIT.info("[wager] {} upgrader session={} wager={}x{} chips={}c target={} odds={}ppm win={}",
                    player.getName().getString(), sessionId, escrow.getCount(),
                    BuiltInRegistries.ITEM.getKey(escrow.getItem()), stakeCents,
                    BuiltInRegistries.ITEM.getKey(target), ppm, decidedWin);
        }
        return true;
    }

    @Override
    public boolean commitWager(ServerPlayer player) { return placeWager(player); }

    @Override
    public boolean acknowledge(long claimedSession) { return finishSpin(claimedSession); }

    public boolean finishSpin(long claimedSession) {
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
        // ROLLING -> SETTLING is the one-way door: a replayed packet finds SETTLING or later and
        // returns at once. The wager is consumed whether the wheel landed on gold or not, so on this
        // table every spin feeds the pot.
        settleHouseWager(() -> ItemCasino.AUDIT.info("[settle] upgrader session={} win={} payout={}",
                sessionId, decidedWin, payout));
    }

    @Override
    protected void materialiseDecidedOutcome() {
        if (!decidedWin || target == null) {
            payout.clear();
        } else {
            // Exactly the count the odds were priced against. A server-wide multiplier on top of it
            // (the old upgrader.output_count) paid N times the prize at the odds of one.
            setPayout(PayoutResolver.exact(target, Math.max(1, outputCount)));

        }
        // A chip stake is spent either way, like an item stake; the card itself comes back.
        payChips(0L);
    }

    /** Prices the wager against the whole prize: n of the target, not one of it. */
    private int quote(ItemStack stack, Item target, ValuationSnapshot snapshot) {
        long unit = snapshot.value(target);
        long prize = unit == Fixed.INF ? Fixed.INF : saturatingMultiply(unit, outputCount);
        return Odds.ppm(inputValue(stack, snapshot), prize);
    }

    /** A stack's worth, or a card's bet in chips: the wager side of the odds either way. */
    private long inputValue(ItemStack stack, ValuationSnapshot snapshot) {
        if (com.itemcasino.chips.ChipCards.isCard(stack)) {
            return com.itemcasino.core.chips.Chips.valueOfCents(
                    com.itemcasino.core.chips.Chips.centsOfChips(effectiveBetChips()));
        }
        return StackValuator.value(stack, snapshot);
    }

    private static long saturatingMultiply(long a, long b) {
        if (a == 0 || b == 0) return 0;
        long product = a * b;
        return a != product / b ? Fixed.INF : product;
    }

    private static long wire(long value) { return value == Fixed.INF ? -1L : value; }

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        if (target != null) {
            Identifier id = BuiltInRegistries.ITEM.getKey(target);
            if (id != null) out.putString("target", id.toString());
        }
        out.putFloat("stop_angle", stopAngle);
        out.putInt("output_count", outputCount);
    }

    @Override
    public void load(ValueInput in) {
        // Read the target first: repairAfterLoad needs it to materialise a win decided before the
        // world unloaded.
        in.getString("target").ifPresent(raw -> {
            Identifier id = Identifier.tryParse(raw);
            if (id != null) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null && item != Items.AIR) this.target = item;
            }
        });
        this.stopAngle = in.getFloatOr("stop_angle", 0F);
        this.outputCount = Math.max(1, in.getIntOr("output_count", 1));
        super.load(in);
    }
}
