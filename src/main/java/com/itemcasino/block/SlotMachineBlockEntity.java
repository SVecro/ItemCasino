package com.itemcasino.block;

import com.itemcasino.menu.SlotMachineMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.SlotMachineSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class SlotMachineBlockEntity extends AbstractCasinoBlockEntity {

    public SlotMachineBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.SLOT_MACHINE.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new SlotMachineSession(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.slot_machine");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SlotMachineMenu(containerId, inventory, session(), player);
    }
}
