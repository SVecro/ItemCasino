package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.session.CasinoSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SimpleContainerData;

import javax.annotation.Nullable;

public class BlackjackMenu extends AbstractCasinoMenu {

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
    public BlackjackMenu(int containerId, Inventory playerInventory, CasinoSession session, Player viewer) {
        this(containerId, playerInventory, session.wagerContainer(),
                serverData(session, viewer), session);
    }

    private BlackjackMenu(int containerId, Inventory playerInventory,
                  net.minecraft.world.Container container,
                  net.minecraft.world.inventory.ContainerData data,
                  @Nullable CasinoSession session) {
        super(CasinoMenus.BLACKJACK_TABLE.get(), containerId, playerInventory, container, data, session, CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
    }
}
