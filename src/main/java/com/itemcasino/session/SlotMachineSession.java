package com.itemcasino.session;

import com.itemcasino.chips.ChipCards;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.Roller;
import com.itemcasino.core.game.slots.SlotMachine;
import com.itemcasino.core.game.slots.SlotOutcome;
import com.itemcasino.network.s2c.S2CSessionStarted;
import com.itemcasino.network.s2c.S2CSlotResult;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Three reels. One lever.
 *
 * <p>The spin happens in full the instant the lever is pulled: three faces come out of the world's
 * random source, the paytable is read, and the multiplier is written down. What follows is four
 * seconds of reels stopping one after another on faces that were already chosen — so a player who
 * closes the screen, crashes, or logs out mid-spin is owed exactly what the machine had already
 * decided, and the restore path pays it.
 *
 * <p>The paytable itself lives in {@code SlotSymbol}, which has no idea Minecraft exists and is
 * enumerated exhaustively by its test: the machine's 10% edge is arithmetic, not a hope.
 */
public class SlotMachineSession extends CasinoSession {

    private int left;
    private int middle;
    private int right;
    private int multiplier;
    private int stagger;

    public SlotMachineSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_SLOT_MACHINE; }

    public int stagger() { return stagger; }

    @Override
    public long maxBetChips() { return CasinoConfig.SERVER.slotMaxStakeChips.get(); }

    /** The three faces packed into one int for the menu's data channel, or -1 when idle. */
    @Override
    public int reelState() {
        if (state == GameState.IDLE || state == GameState.ARMED) return -1;
        return left | (middle << 4) | (right << 8);
    }

    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        setState(wagerStack().isEmpty() ? GameState.IDLE : GameState.ARMED);
    }

    public boolean placeWager(ServerPlayer player) {
        if (state != GameState.ARMED) return false;
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
        // The top prize is hundreds of times the stake. Without a ceiling on what goes in, one
        // lucky pull on a full stack of something valuable empties a server's economy into one
        // player's inventory -- and five hundred stacks do not fit in it anyway. A chip bet is
        // capped by maxBetChips instead.
        int maxStake = CasinoConfig.SERVER.slotMaxStake.get();
        if (!ChipCards.isCard(stack) && stack.getCount() > maxStake) {
            player.displayClientMessage(
                    Component.translatable("itemcasino.reject.stake_too_large", maxStake), true);
            return false;
        }

        this.frozenSnapshot = snapshot;
        this.sessionId++;
        escrowStake();
        if (!setState(GameState.LOCKED)) return false;

        RandomSource rng = host.random();
        Roller roller = rng::nextInt;
        SlotOutcome outcome = SlotMachine.spin(roller);
        this.left = outcome.left();
        this.middle = outcome.middle();
        this.right = outcome.right();
        this.multiplier = outcome.multiplier();
        this.decidedWin = outcome.isWin();
        this.frozenPpm = 0;
        this.spinTicks = CasinoConfig.SERVER.slotSpinTicks.get();
        this.stagger = CasinoConfig.SERVER.slotReelStagger.get();
        setState(GameState.ROLLING);
        // The last reel stops two staggers after the first, so the deadline has to outlast all of it.
        this.deadlineTick = host.hostLevel().getGameTime() + spinTicks + 2L * stagger + 40L;
        beginCommit(player, spinTicks + 2L * stagger);
        touch();
        onWagerCommitted(player);

        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), frozenPpm));
        host.broadcast(id -> new S2CSlotResult(id, sessionId, (byte) left, (byte) middle,
                (byte) right, spinTicks, stagger));

        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.LOGGER.info("[wager] {} slots session={} wager={}x{} chips={}c reels={}/{}/{} pays={}x",
                    player.getName().getString(), sessionId, escrow.getCount(),
                    BuiltInRegistries.ITEM.getKey(escrow.getItem()), stakeCents,
                    outcome.leftSymbol(), outcome.middleSymbol(), outcome.rightSymbol(), multiplier);
        }
        return true;
    }

    /** The paytable's own sense of a big hit: the gold triple and up. */
    @Override
    protected byte winTier() {
        return multiplier >= 25 ? com.itemcasino.network.s2c.S2CPayoutReady.TIER_BIG
                : com.itemcasino.network.s2c.S2CPayoutReady.TIER_WIN;
    }

    public boolean finishSpin(long claimedSession) {
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
        materialiseDecidedOutcome();
        recordOutcome(stakedValue(), returnedValue());
        bankLoss();
        escrow = ItemStack.EMPTY;
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        returnCardsToSlots();
        touch();
        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.LOGGER.info("[settle] slots session={} multiplier={} payout={}",
                    sessionId, multiplier, payout);
        }
        broadcastPayout();
    }

    /**
     * The faces are re-read rather than the multiplier trusted, so a save file that was edited to
     * claim a jackpot still only pays what its own three reels say.
     */
    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        if (escrow.isEmpty()) return;
        int pays = SlotMachine.evaluate(left, middle, right).multiplier();
        if (stakeIsChips()) {
            payChips(Chips.payout(stakeCents, Math.max(0, pays), 1));
            return;
        }
        if (pays <= 0) return;
        setPayout(PayoutResolver.multiply(escrow, pays, 1, snapshot()));
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.putInt("reel_left", left);
        out.putInt("reel_middle", middle);
        out.putInt("reel_right", right);
        out.putInt("multiplier", multiplier);
        out.putInt("stagger", stagger);
    }

    @Override
    public void load(ValueInput in) {
        // Before the base class, which finishes by repairing a session restored mid-spin -- and
        // that repair reads the faces to work out what it owes.
        left = in.getIntOr("reel_left", 0);
        middle = in.getIntOr("reel_middle", 0);
        right = in.getIntOr("reel_right", 0);
        multiplier = in.getIntOr("multiplier", 0);
        stagger = in.getIntOr("stagger", 0);
        super.load(in);
    }
}
