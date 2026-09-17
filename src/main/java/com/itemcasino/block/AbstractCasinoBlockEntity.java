package com.itemcasino.block;

import com.itemcasino.core.game.GameState;
import com.itemcasino.menu.AbstractCasinoMenu;
import com.itemcasino.network.CasinoNetwork;
import com.itemcasino.player.CasinoMailbox;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.SessionHost;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * A casino table in the world: a {@link SessionHost} that anyone can gather around.
 *
 * <h2>One seat, many spectators</h2>
 * A table used to refuse a second player outright. It now opens for everyone: the first arrival
 * takes the seat and is the only one who can touch the slot or press a button, and everyone else
 * watches the same wheel, the same cards and the same payout in real time. That is the whole point
 * of a casino being a place rather than a menu.
 *
 * <h2>Whose items are these</h2>
 * A seat is owned by a UUID, and everything staked from it belongs to that UUID — never to whoever
 * happens to have the screen open. Three rules follow, and each closes a way items used to change
 * hands:
 * <ol>
 *   <li>Closing the screen with nothing committed gives the seat up <em>and hands the stake back</em>.
 *       The stake used to stay in the slot for the next person to sit down and pick up.</li>
 *   <li>A seat whose holder is gone is swept: an uncommitted stake goes back to them, and a win
 *       they have not collected goes to their casino mailbox once the table has been idle for
 *       {@code abandon_seconds}. Before, such a seat stayed locked for good.</li>
 *   <li>However the table is destroyed — a player, an explosion, {@code /setblock}, another mod — a
 *       decided outcome is settled first and everything is handed to its owner. Only items nobody
 *       owns fall on the floor.</li>
 * </ol>
 */
public abstract class AbstractCasinoBlockEntity extends BlockEntity
        implements MenuProvider, SessionHost {

    /** Data slot indices shared with the menu's {@code ContainerData}. */
    public static final int DATA_STATE = 0;
    public static final int DATA_ODDS_PPM = 1;
    public static final int DATA_HAS_PAYOUT = 2;
    public static final int DATA_SPIN_TICKS = 3;
    /** 1 when the player this menu belongs to holds a seat; 0 for a spectator. */
    public static final int DATA_CAN_ACT = 4;
    /** Which seat this viewer holds, plus one, so 0 means "none" over the int-only channel. */
    public static final int DATA_SEAT = 5;
    /** A game's own setting: the dice bet, the Vault's share, the mine count. */
    public static final int DATA_OPTION = 6;
    /** What each seat has staked, in thousandths of a unit, or -1. Display only, see below. */
    public static final int DATA_STAKE_A = 7;
    public static final int DATA_STAKE_B = 8;
    /** The slot machine's three faces packed into one int, or -1 while it is idle. */
    public static final int DATA_REELS = 9;
    /** Three game-specific read-outs, see {@code CasinoSession#readout}. */
    public static final int DATA_AUX_A = 10;
    public static final int DATA_AUX_B = 11;
    public static final int DATA_AUX_C = 12;
    public static final int DATA_AUX_D = 13;
    public static final int DATA_AUX_E = 14;
    public static final int DATA_AUX_F = 15;
    /** The viewer's chip bet, in whole chips. */
    public static final int DATA_BET = 16;
    /** The table's own cap on one chip bet, so the bet bar's Max stops where the server would. */
    public static final int DATA_MAX_BET = 17;
    public static final int DATA_COUNT = 18;

    private final CasinoSession session;
    private final Set<UUID> viewers = new LinkedHashSet<>();
    /** One entry per seat the game has. Null means free. */
    private final UUID[] seats;

    protected AbstractCasinoBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.session = createSession();
        this.seats = new UUID[Math.max(1, session.seats())];
    }

    /** Built once in the constructor; the host reference is this block entity. */
    protected abstract CasinoSession createSession();

    public CasinoSession session() { return session; }

    // ------------------------------------------------------------------ SessionHost

    @Override
    public ServerLevel hostLevel() {
        return (ServerLevel) this.level;
    }

    @Override
    public void markDirty() {
        setChanged();
    }

    @Override
    public void broadcast(IntFunction<CustomPacketPayload> factory) {
        if (!(this.level instanceof ServerLevel serverLevel)) return;
        for (UUID id : Set.copyOf(viewers)) {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(id);
            if (player == null) { viewers.remove(id); continue; }
            if (!(player.containerMenu instanceof AbstractCasinoMenu menu)
                    || menu.session() != session) {
                viewers.remove(id);
                continue;
            }
            CasinoNetwork.send(player, factory.apply(player.containerMenu.containerId));
        }
    }

    @Nullable
    @Override
    public ServerPlayer seatedPlayer() {
        return seatedPlayer(0);
    }

    @Nullable
    @Override
    public UUID seatId(int index) {
        return index >= 0 && index < seats.length ? seats[index] : null;
    }

    /** The player in one particular seat, if they are online and still watching this session. */
    @Nullable
    public ServerPlayer seatedPlayer(int index) {
        if (index < 0 || index >= seats.length || seats[index] == null) return null;
        if (!(this.level instanceof ServerLevel serverLevel)) return null;
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(seats[index]);
        if (player == null) return null;
        return player.containerMenu instanceof AbstractCasinoMenu menu && menu.session() == session
                ? player : null;
    }

    @Override
    public boolean isSeated(@Nullable Player player) {
        return seatIndex(player) >= 0;
    }

    @Override
    public int seatIndex(@Nullable Player player) {
        if (player == null) return -1;
        UUID id = player.getUUID();
        for (int i = 0; i < seats.length; i++) {
            if (id.equals(seats[i])) return i;
        }
        return -1;
    }

    @Override
    public boolean stillValid(Player player) {
        if (isRemoved()) return false;
        // Same reach vanilla containers use: walk away and the screen closes itself.
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0D;
    }

    @Override
    public void dropOverflow(ItemStack stack) {
        if (this.level instanceof ServerLevel serverLevel) {
            Containers.dropItemStack(serverLevel, worldPosition.getX() + 0.5,
                    worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, stack);
        }
    }

    // ------------------------------------------------------------------ seating

    /** Everyone may look. Arrivals take the free seats in order; the rest spectate. */
    public void openFor(ServerPlayer player) {
        // Anything the casino kept for this player comes back the moment they sit at any table.
        CasinoMailbox.deliverAndNotify(player);
        viewers.add(player.getUUID());
        if (seatIndex(player) < 0) {
            for (int i = 0; i < seats.length; i++) {
                if (seats[i] == null) {
                    seats[i] = player.getUUID();
                    session.touch();
                    break;
                }
            }
        }
        setChanged();
    }

    @Override
    public void onViewerClosed(Player player) {
        viewers.remove(player.getUUID());
        int index = seatIndex(player);
        if (index < 0) { setChanged(); return; }
        // A seat is kept while something is committed or owed: standing up mid-hand would hand the
        // wager to whoever sat down next. Otherwise the seat is released and the stake goes with
        // its owner — to their inventory, or to their mailbox if they are disconnecting, since a
        // disconnecting player's inventory has already been saved.
        if (session.gameState().acceptsItems() && !session.hasPayout() && !session.hasLiveWager()) {
            releaseSeat(index);
        }
        setChanged();
    }

    /** Frees one seat and returns whatever is in its slot to the seat's owner. */
    private void releaseSeat(int index) {
        UUID owner = seats[index];
        ItemStack stake = session.takeSlot(index);
        seats[index] = null;
        if (!stake.isEmpty()) deliverOrDrop(owner, List.of(stake));
    }

    /** To the owner (inventory or mailbox), or onto the table's floor when nobody can be named. */
    private void deliverOrDrop(@Nullable UUID owner, List<ItemStack> stacks) {
        if (stacks.isEmpty()) return;
        boolean sent = this.level instanceof ServerLevel serverLevel
                && CasinoMailbox.send(serverLevel.getServer(), owner, stacks);
        if (!sent) stacks.forEach(this::dropOverflow);
    }

    /**
     * Seats whose holder is not at the table any more.
     *
     * <p>Normally closing the screen releases a seat, so this only finds seats left behind by a crash,
     * by a save from before that rule, or by a player who walked off with a win uncollected.
     */
    private void sweepAbsentSeats() {
        if (session.gameState().holdsEscrow()) return;          // the deadline settles it first
        boolean stale = session.isStale();
        for (int i = 0; i < seats.length; i++) {
            UUID owner = seats[i];
            if (owner == null || seatedPlayer(i) != null) continue;

            if (session.hasPayout()) {
                UUID payee = session.payoutOwner();
                if (owner.equals(payee)) {
                    if (!stale) continue;
                    // Uncollected and abandoned: to the winner's mailbox, and the table is free.
                    deliverOrDrop(owner, session.takePayout());
                    seats[i] = null;
                } else {
                    // A losing duellist has nothing here and should not keep the chair.
                    seats[i] = null;
                }
                setChanged();
                continue;
            }
            if (session.gameState().acceptsItems()) {
                releaseSeat(i);
                setChanged();
            }
        }
    }

    @Nullable public UUID seat() { return seats[0]; }

    /** True when every seat is taken: a duel cannot start until it is. */
    public boolean seatsFull() {
        for (UUID id : seats) {
            if (id == null) return false;
        }
        return true;
    }

    public int viewerCount() { return viewers.size(); }

    public boolean hasLiveWager() { return session.hasLiveWager(); }

    // ------------------------------------------------------------------ world lifecycle

    public static void serverTick(Level level, BlockPos pos, BlockState blockState,
                                  AbstractCasinoBlockEntity be) {
        if (!(level instanceof ServerLevel)) return;
        be.session.tick();
        if (level.getGameTime() % 20L == 0L) be.sweepAbsentSeats();
    }

    /**
     * Called by the chunk for <em>every</em> removal of this block, whatever caused it. This is the
     * one place a table can be torn down without its items going missing, which is why the work is
     * here and not in a break event that only players fire.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this.level instanceof ServerLevel) spillEverything();
    }

    /**
     * Settles anything already decided, then hands every item to its owner.
     *
     * <p>Settling first matters: the client learns a spin's outcome when it starts, so refunding a
     * committed stake on destruction let a player blow up a table whose wheel was about to land on
     * a loss. A blackjack hand still in play is stood, as its timer would have done.
     */
    public void spillEverything() {
        try {
            if (session.gameState().holdsEscrow()) session.forceSettle();
        } catch (RuntimeException e) {
            com.itemcasino.ItemCasino.LOGGER.error("Could not settle a casino table being removed", e);
        }
        if (session.hasPayout()) {
            UUID payee = session.payoutOwner();
            deliverOrDrop(payee, session.takePayout());
        }
        for (int i = 0; i < seats.length; i++) {
            ItemStack stake = session.takeSlotUnchecked(i);
            if (!stake.isEmpty()) deliverOrDrop(seats[i], new ArrayList<>(List.of(stake)));
        }
        // Whatever is left has no owner the session can name (an escrow a settle could not
        // resolve): the floor, as before.
        session.liquidate(null);
        java.util.Arrays.fill(seats, null);
        viewers.clear();
        setChanged();
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        for (int i = 0; i < seats.length; i++) {
            if (seats[i] != null) out.putString("seat" + i, seats[i].toString());
        }
        session.save(out);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        for (int i = 0; i < seats.length; i++) {
            seats[i] = in.getString("seat" + i).map(AbstractCasinoBlockEntity::parseUuid).orElse(null);
        }
        // Worlds saved before the table could seat more than one player wrote a single "seat".
        if (seats[0] == null) {
            seats[0] = in.getString("seat").map(AbstractCasinoBlockEntity::parseUuid).orElse(null);
        }
        session.load(in);
    }

    @Nullable
    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
