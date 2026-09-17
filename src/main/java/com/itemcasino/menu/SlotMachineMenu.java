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

public class SlotMachineMenu extends AbstractCasinoMenu {

    public SlotMachineMenu(int containerId, Inventory playerInventory,
                           RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, new SimpleContainer(1),
                new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT), null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    public SlotMachineMenu(int containerId, Inventory playerInventory, CasinoSession session,
                           Player viewer) {
        this(containerId, playerInventory, session.wagerContainer(),
                serverData(session, viewer), session);
    }

    private SlotMachineMenu(int containerId, Inventory playerInventory, Container container,
                            ContainerData data, @Nullable CasinoSession session) {
        super(CasinoMenus.SLOT_MACHINE.get(), containerId, playerInventory, container, data,
                session, CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
    }
}
