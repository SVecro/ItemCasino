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
 * <p>There is exactly one slot, and it is the viewer's own. The neighbours' <em>hands</em> are drawn
 * on the felt from the packet; their stakes are not shown, because 202 pixels of felt has no free
 * 16 by 16 left once the dealer, three hands and the action row have had their share. Nothing is
 * lost functionally: a neighbour bets through their own screen, into this same slot bound to their
 * own chair.
 */
public class BlackjackMenu extends AbstractCasinoMenu {

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
        this(containerId, playerInventory, new SimpleContainer(1),
                new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT), null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    /** Server constructor, shared by the table and the pocket device. */
    public BlackjackMenu(int containerId, Inventory playerInventory, CasinoSession session,
                         Player viewer) {
        this(containerId, playerInventory, ownBox(session, viewer),
                serverData(session, viewer), session);
    }

    private BlackjackMenu(int containerId, Inventory playerInventory, Container own,
                          ContainerData data, @Nullable CasinoSession session) {
        super(CasinoMenus.BLACKJACK_TABLE.get(), containerId, playerInventory, own, data, session,
                CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
    }

    /** This viewer's own box, or a throwaway one for a spectator, who may fill nothing. */
    private static Container ownBox(CasinoSession session, Player viewer) {
        Container container = session.seatContainer(session.seatIndex(viewer));
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
