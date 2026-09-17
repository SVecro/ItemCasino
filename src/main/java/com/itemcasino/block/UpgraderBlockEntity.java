package com.itemcasino.block;

import com.itemcasino.menu.UpgraderMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.UpgraderSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class UpgraderBlockEntity extends AbstractCasinoBlockEntity {

    public UpgraderBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.UPGRADER.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new UpgraderSession(this);
    }

    public UpgraderSession upgrader() {
        return (UpgraderSession) session();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.upgrader");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new UpgraderMenu(containerId, inventory, session(), player);
    }
}
