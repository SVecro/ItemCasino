package com.itemcasino.block;

import com.itemcasino.menu.VaultMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.VaultSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class VaultBlockEntity extends AbstractCasinoBlockEntity {

    public VaultBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.VAULT.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new VaultSession(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.vault");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new VaultMenu(containerId, inventory, session(), player);
    }
}
