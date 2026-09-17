package com.itemcasino.session;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.game.GameState;
import com.itemcasino.core.game.MineField;
import com.itemcasino.core.game.Roller;
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
 * The mine field: a five by five board, a few hidden mines, and a payout that climbs with every
 * safe tile turned over — until the player cashes out, or finds a mine and loses the stake.
 *
 * <h2>What is decided when</h2>
 * The mines are laid the instant the stake is committed, from the server's random source, and never
 * leave the server until the board is over: the client is told which tiles were turned over and
 * what they were, nothing else. What remains is the player's choice of tiles and of when to stop,
 * and a choice is not a random outcome, so the rule that everything is decided at commit holds.
 *
 * <h2>Why a restart cashes out</h2>
 * A board in progress is persisted as its layout and the tiles turned so far. On load it is cashed
 * out at the multiplier those tiles had earned, exactly what the action timer would have done. That
 * is never better for the player than cashing out themselves — which they could do at any moment —
 * so pulling the plug is not an option worth anything, and a mine found is settled in the same tick
 * it is found, before there is anything to save.
 */
public class MineFieldSession extends CasinoSession {

    public static final int READOUT_REVEALED = 0;
    public static final int READOUT_MINES = 1;
    public static final int READOUT_MULTIPLIER = 2;
    public static final int READOUT_NEXT = 3;
    public static final int READOUT_TOKEN = 4;
    public static final int READOUT_STATUS = 5;

    public static final int OUTCOME_NONE = 0;
    public static final int OUTCOME_CASHED = 1;
    public static final int OUTCOME_BOOM = 2;

    /** The number of mines the next board is laid with: a table setting, chosen before commit. */
    private int mines = -1;

    // The board in play, or the last one, kept on show until the next stake changes it.
    private int boardMines;
    private int layout;
    private int revealed;
    private int boardEdgePpm;
    private long boardCapPpm;
    private long boardCeilingCents;
    private int lastTile = -1;
    private int outcome = OUTCOME_NONE;

    public MineFieldSession(SessionHost host) {
        super(host);
    }

    @Override
    public byte gameType() { return S2CSessionStarted.GAME_MINE_FIELD; }

    // ------------------------------------------------------------------ read-outs

    public int mines() {
        if (mines < 0) mines = MineField.clampMines(CasinoConfig.SERVER.mineDefaultMines.get());
        return mines;
    }

    /** The mine count for the next board. */
    @Override
    public int option() { return mines(); }

    /** Refused once a board is running: the payout table was fixed with the mines. */
    @Override
    public void setOption(ServerPlayer player, int value) {
        if (!state.acceptsItems()) return;
        int clamped = MineField.clampMines(value);
        if (clamped == mines()) return;
        mines = clamped;
        clearBoard();
        host.markDirty();
    }

    /** The house edge applies to every multiplier; the screen explains it, so it is shown live. */
    @Override
    public int frozenPpm() {
        return holdsBoard() ? boardEdgePpm : CasinoConfig.SERVER.mineEdgePpm.get();
    }

    @Override
    public int stakeMilli(int seat) {
        if (seat != 0) return -1;
        long value = stakeValue();
        if (value < 0 || value == com.itemcasino.core.value.Fixed.INF) return -1;
        return (int) Math.min(Integer.MAX_VALUE, value / 1000L);
    }

    /** The mine field is played in chips only: its multipliers are rarely whole numbers of items. */
    @Override
    public boolean chipsOnly() { return true; }

    @Override
    public long maxBetChips() {
        // Set for the mines chosen now; a board in play keeps the ceiling it started with.
        long cents = MineField.maxStakeCents(mines(), CasinoConfig.SERVER.mineEdgePpm.get(),
                CasinoConfig.SERVER.mineMaxMultiplier.get() * MineField.PPM, ceilingCents());
        return Math.max(1, cents / com.itemcasino.core.chips.Chips.CENTS);
    }

    private static long ceilingCents() {
        return com.itemcasino.core.chips.Chips.centsOfChips(CasinoConfig.SERVER.mineMaxPayoutChips.get());
    }

    /** One more tile would pay past the ceiling this board started with. */
    private boolean overCeiling() {
        return MineField.nextTileOverCeiling(boardMines, revealedCount(), boardEdgePpm, boardCapPpm,
                stakeCents, boardCeilingCents);
    }

    @Override
    public int readout(int index) {
        return switch (index) {
            case READOUT_REVEALED -> revealed;
            // The layout is the one secret on this table: it is shown only once the board is over.
            case READOUT_MINES -> outcome != OUTCOME_NONE ? layout : 0;
            case READOUT_MULTIPLIER -> (int) currentMultiplierPpm();
            case READOUT_NEXT -> (int) nextMultiplierPpm();
            case READOUT_TOKEN -> token(sessionId);
            case READOUT_STATUS -> packStatus(lastTile, outcome, frozenPpm());
            default -> 0;
        };
    }

    /** What the client quotes back with each click, so a click aimed at one board never lands on the next. */
    public static int token(long sessionId) {
        return (int) (sessionId & 0x7FFF_FFFFL);
    }

    /** Last tile (+1, so 0 is none) in bits 0-4, the outcome in 5-6, the edge in 0.01 % in 7-19. */
    public static int packStatus(int lastTile, int outcome, int edgePpm) {
        int tile = MineField.isTile(lastTile) ? lastTile + 1 : 0;
        return tile | (outcome & 0b11) << 5 | Math.min(0x1FFF, Math.max(0, edgePpm) / 100) << 7;
    }

    private boolean holdsBoard() {
        return state == GameState.ROLLING || state == GameState.LOCKED || state == GameState.SETTLING;
    }

    public int revealedCount() { return Integer.bitCount(revealed); }

    /**
     * What cashing out would pay now: the board's multiplier while it runs and after a cash-out, zero
     * after a mine, and x1 before anything is at stake.
     */
    public long currentMultiplierPpm() {
        if (outcome == OUTCOME_BOOM) return 0;
        if (!holdsBoard() && outcome == OUTCOME_NONE) return MineField.PPM;
        return MineField.multiplierPpm(boardMines, revealedCount(), boardEdgePpm, boardCapPpm);
    }

    /**
     * What one more safe tile would pay on the board in play, or what the first tile of the next
     * board pays; zero once the board in play has nothing left to give.
     */
    public long nextMultiplierPpm() {
        if (holdsBoard()) {
            if (MineField.isFinished(boardMines, revealedCount(), boardEdgePpm, boardCapPpm) || overCeiling()) return 0;
            return MineField.multiplierPpm(boardMines, revealedCount() + 1, boardEdgePpm, boardCapPpm);
        }
        return MineField.multiplierPpm(mines(), 1, CasinoConfig.SERVER.mineEdgePpm.get(),
                CasinoConfig.SERVER.mineMaxMultiplier.get() * MineField.PPM);
    }

    /** For tests: the hidden layout of the board in play. */
    public int layoutForTest() { return layout; }

    // ------------------------------------------------------------------ the board

    /**
     * The finished board stays on show while the player decides on the next bet: the card comes
     * straight back into the slot after a mine, and wiping the board then would hide where the mines
     * were at the very moment the player wants to see it. The next Start lays a fresh board.
     */
    @Override
    protected void onWagerChanged() {
        if (state == GameState.LOCKED || state == GameState.ROLLING) return;
        setState(wagerStack().isEmpty() ? GameState.IDLE : GameState.ARMED);
    }

    private void clearBoard() {
        if (holdsBoard()) return;
        revealed = 0;
        layout = 0;
        lastTile = -1;
        outcome = OUTCOME_NONE;
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
        escrowStake();
        if (!setState(GameState.LOCKED)) return false;

        this.boardMines = mines();
        this.boardEdgePpm = CasinoConfig.SERVER.mineEdgePpm.get();
        this.boardCapPpm = CasinoConfig.SERVER.mineMaxMultiplier.get() * MineField.PPM;
        this.boardCeilingCents = ceilingCents();
        Roller roller = host.random()::nextInt;
        this.layout = MineField.layout(boardMines, roller);
        this.revealed = 0;
        this.lastTile = -1;
        this.outcome = OUTCOME_NONE;
        this.decidedWin = false;
        this.spinTicks = 0;
        if (!setState(GameState.ROLLING)) return false;
        resetActionDeadline();
        beginCommit(player, 0L);
        touch();

        host.broadcast(id -> new S2CSessionStarted(id, sessionId, gameType(), boardEdgePpm));
        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.LOGGER.info("[wager] {} mines session={} stake={}c mines={}",
                    player.getName().getString(), sessionId, stakeCents, boardMines);
        }
        return true;
    }

    /**
     * Turns a tile over. Legality comes from the server's own board: the token must name the board
     * in play, the tile must exist and must not already be turned.
     */
    public boolean reveal(ServerPlayer player, int claimedToken, int tile) {
        if (state != GameState.ROLLING) return false;
        if (claimedToken != token(sessionId)) return false;
        if (!isSeated(player)) return false;
        if (!MineField.isTile(tile) || (revealed >>> tile & 1) != 0) return false;
        // Only reachable with a stake the table would not take today, e.g. after a config change.
        if (overCeiling()) return false;

        // The first tile is where the risk starts, so that is where the ambient jackpot chance is
        // offered. On commit it would be a free ticket: commit, cash out at x1, repeat.
        if (revealed == 0) onWagerCommitted(player);
        if (state != GameState.ROLLING) return false;

        lastTile = tile;
        touch();
        if (MineField.isMine(layout, tile)) {
            outcome = OUTCOME_BOOM;
            settle();
            return true;
        }
        revealed |= 1 << tile;
        host.markDirty();
        if (MineField.isFinished(boardMines, revealedCount(), boardEdgePpm, boardCapPpm) || overCeiling()) {
            // Nothing left worth turning, or nothing the ceiling lets it pay: the board cashes itself
            // out rather than ask the player to click a button that can only be the right answer.
            outcome = OUTCOME_CASHED;
            settle();
        } else {
            resetActionDeadline();
        }
        return true;
    }

    public boolean cashOut(ServerPlayer player, int claimedToken) {
        if (state != GameState.ROLLING) return false;
        if (claimedToken != token(sessionId)) return false;
        if (!isSeated(player)) return false;
        outcome = OUTCOME_CASHED;
        settle();
        return true;
    }

    /** An absent player is cashed out, never forfeited: stopping is always their safe choice. */
    @Override
    public void forceSettle() {
        if (state != GameState.ROLLING) return;
        outcome = OUTCOME_CASHED;
        settle();
    }

    private void settle() {
        if (!setState(GameState.SETTLING)) return;
        decidedWin = outcome == OUTCOME_CASHED && revealed != 0;
        materialiseDecidedOutcome();
        recordOutcome(stakedValue(), returnedValue());
        bankLoss();
        escrow = ItemStack.EMPTY;
        deadlineTick = 0;
        setState(payout.isEmpty() ? GameState.IDLE : GameState.PAYOUT_PENDING);
        returnCardsToSlots();
        touch();
        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.LOGGER.info("[settle] mines session={} mines={} tiles={} outcome={} payout={}",
                    sessionId, boardMines, revealedCount(),
                    outcome == OUTCOME_BOOM ? "BOOM" : "cashed", payout);
        }
        broadcastPayout();
    }

    /**
     * Re-derived from the board rather than from a stored multiplier: the tiles turned are counted
     * again, and any turned tile that is a mine (which only an edited save could produce) pays
     * nothing.
     */
    @Override
    protected void materialiseDecidedOutcome() {
        payout.clear();
        if (escrow.isEmpty()) return;
        if (outcome == OUTCOME_BOOM || (revealed & layout) != 0) {
            payChips(0L);
            return;
        }
        long multiplier = MineField.multiplierPpm(boardMines, revealedCount(), boardEdgePpm, boardCapPpm);
        payChips(com.itemcasino.core.chips.Chips.payout(stakeCents, multiplier));
    }

    @Override
    protected void repairAfterLoad() {
        if (state.holdsEscrow() && outcome == OUTCOME_NONE) outcome = OUTCOME_CASHED;
        super.repairAfterLoad();
    }

    private void resetActionDeadline() {
        deadlineTick = host.hostLevel().getGameTime()
                + CasinoConfig.SERVER.mineActionSeconds.get() * 20L;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void save(ValueOutput out) {
        super.save(out);
        out.putInt("mines", mines());
        out.putInt("board_mines", boardMines);
        out.putInt("board_layout", layout);
        out.putInt("board_revealed", revealed);
        out.putInt("board_edge_ppm", boardEdgePpm);
        out.putLong("board_cap_ppm", boardCapPpm);
        out.putLong("board_ceiling_cents", boardCeilingCents);
        out.putInt("board_last_tile", lastTile);
        out.putInt("board_outcome", outcome);
    }

    @Override
    public void load(ValueInput in) {
        // Before the base class, whose repair cashes out a board restored mid-play.
        int savedMines = in.getIntOr("mines", -1);
        mines = savedMines < 0 ? -1 : MineField.clampMines(savedMines);
        boardMines = MineField.clampMines(in.getIntOr("board_mines", MineField.MIN_MINES));
        layout = in.getIntOr("board_layout", 0) & ((1 << MineField.TILES) - 1);
        revealed = in.getIntOr("board_revealed", 0) & ((1 << MineField.TILES) - 1);
        boardEdgePpm = Math.max(0, Math.min(500_000, in.getIntOr("board_edge_ppm", 30_000)));
        boardCapPpm = Math.max(MineField.PPM, Math.min(2_000L * MineField.PPM,
                in.getLongOr("board_cap_ppm", 250L * MineField.PPM)));
        boardCeilingCents = Math.max(1, in.getLongOr("board_ceiling_cents", 25_000_000L));
        lastTile = in.getIntOr("board_last_tile", -1);
        outcome = Math.max(OUTCOME_NONE, Math.min(OUTCOME_BOOM, in.getIntOr("board_outcome", OUTCOME_NONE)));
        super.load(in);
    }
}
