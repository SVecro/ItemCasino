package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.CoinFlipSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

import javax.annotation.Nullable;

/**
 * Two stakes, one table.
 *
 * <p>The second slot is added after the player inventory rather than beside the first, so the slot
 * indices the rest of the menu machinery relies on — slot zero is a wager, one to thirty-six are
 * the inventory — keep meaning what they meant. Seat B's slot is simply the last one.
 */
public class CoinFlipMenu extends AbstractCasinoMenu {

    /** Seat B's stake: the slot added after the thirty-six inventory slots. */
    public static final int WAGER_SLOT_B = INVENTORY_END;

    public CoinFlipMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, new SimpleContainer(1), new SimpleContainer(1),
                new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT), null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    public CoinFlipMenu(int containerId, Inventory playerInventory, CasinoSession session,
                        Player viewer) {
        this(containerId, playerInventory, session.wagerContainer(),
                ((CoinFlipSession) session).wagerContainerB(),
                serverData(session, viewer), session);
    }

    private CoinFlipMenu(int containerId, Inventory playerInventory, Container containerA,
                         Container containerB, ContainerData data, @Nullable CasinoSession session) {
        super(CasinoMenus.COIN_FLIP.get(), containerId, playerInventory, containerA, data, session,
                CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
        addSlot(new LockableSlot(containerB, 0, CasinoLayout.WAGER_B_X, CasinoLayout.WAGER_Y,
                () -> gameState().acceptsItems() && seatIndex() == 1, this::acceptsInSlot));
    }

    @Override
    public int ownWagerSlot() {
        return seatIndex() == 1 ? WAGER_SLOT_B : WAGER_SLOT;
    }

    /**
     * A duellist may only fill their own slot. The base class asks this before moving anything, so
     * a shift-click from a spectator, or from the player in the other chair, has nowhere to go.
     */
    @Override
    protected int wagerSlotFor(Player player) {
        int seat = seatIndex();
        return seat < 0 ? -1 : (seat == 1 ? WAGER_SLOT_B : WAGER_SLOT);
    }
}
