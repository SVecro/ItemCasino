package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.session.CasinoSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

import javax.annotation.Nullable;

/**
 * Up to three chairs at one table.
 *
 * <p>The menu is built per viewer, so it can put <em>their</em> chair in front of them: the base
 * wager slot is bound to whichever seat this player holds and stays where a solo table has always
 * put it, and the other two chairs' stakes are added afterwards, on the felt to the left and the
 * right, sealed — you can see what your neighbours bet, never touch it.
 *
 * <p>The two neighbour slots exist even at a one-seat table (the pocket device), bound to throwaway
 * containers and never openable. The slot count has to be the same on both sides of the wire, and
 * the client builds its menu before it knows how many chairs the table has; two empty slots the
 * screen never draws are a far smaller price than a menu whose two sides disagree about what is in
 * which slot, which is how duplication starts.
 */
public class BlackjackMenu extends AbstractCasinoMenu {

    /** The neighbours' stakes: the two slots added after the thirty-six inventory slots. */
    public static final int NEIGHBOUR_SLOT_LEFT = INVENTORY_END;
    public static final int NEIGHBOUR_SLOT_RIGHT = INVENTORY_END + 1;

    /**
     * Which chair is shown at each of the three places — left, centre, right — from the point of
     * view of the player in {@code ownSeat}. The centre is always the viewer's own chair, and the
     * table order is kept going round, so two players facing each other agree about who is where.
     *
     * <p>A spectator has no chair, so they are shown the table from seat 1's place. An entry of -1
     * means there is nothing to draw there.
     */
    public static int[] seatsAround(int ownSeat, int seats) {
        if (seats <= 1) return new int[] { -1, 0, -1 };
        int centre = ownSeat >= 0 && ownSeat < seats ? ownSeat : Math.min(1, seats - 1);
        return new int[] { (centre + seats - 1) % seats, centre, (centre + 1) % seats };
    }

    /**
     * Client constructor. The leading boolean says whether this menu came from a pocket device or
     * a block; the block position that follows is not needed client-side, but reading it keeps the
     * two encodings symmetrical and the buffer fully consumed.
     */
    public BlackjackMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, new SimpleContainer(1), new SimpleContainer(1),
                new SimpleContainer(1), new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT),
                null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    /** Server constructor, shared by the table and the pocket device. */
    public BlackjackMenu(int containerId, Inventory playerInventory, CasinoSession session,
                         Player viewer) {
        this(containerId, playerInventory,
                seatContainer(session, placesFor(session, viewer)[1]),
                seatContainer(session, placesFor(session, viewer)[0]),
                seatContainer(session, placesFor(session, viewer)[2]),
                serverData(session, viewer), session);
    }

    private BlackjackMenu(int containerId, Inventory playerInventory, Container centre,
                          Container left, Container right, ContainerData data,
                          @Nullable CasinoSession session) {
        super(CasinoMenus.BLACKJACK_TABLE.get(), containerId, playerInventory, centre, data, session,
                CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
        addSlot(new LockableSlot(left, 0, CasinoLayout.NEIGHBOUR_LEFT_X, CasinoLayout.NEIGHBOUR_Y,
                () -> false, this::acceptsInSlot).shownWhen(() -> seatCount() > 1));
        addSlot(new LockableSlot(right, 0, CasinoLayout.NEIGHBOUR_RIGHT_X, CasinoLayout.NEIGHBOUR_Y,
                () -> false, this::acceptsInSlot).shownWhen(() -> seatCount() > 1));
    }

    private static int[] placesFor(CasinoSession session, Player viewer) {
        return seatsAround(session.seatIndex(viewer), session.seats());
    }

    /** A chair's own slot container, or a throwaway for a place with no chair behind it. */
    private static Container seatContainer(CasinoSession session, int seat) {
        Container container = session.seatContainer(seat);
        return container != null ? container : new SimpleContainer(1);
    }

    /**
     * The base slot is whichever chair this viewer holds, so it is theirs whenever they have one.
     * A spectator's centre slot is a neighbour's, and stays sealed.
     */
    @Override
    protected boolean ownsBaseSlot() {
        return seatIndex() >= 0;
    }

    @Override
    public int ownWagerSlot() {
        return seatIndex() >= 0 ? WAGER_SLOT : -1;
    }

    @Override
    protected int wagerSlotFor(Player player) {
        return seatIndex() >= 0 ? WAGER_SLOT : -1;
    }
}
