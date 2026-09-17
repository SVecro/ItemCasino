package com.itemcasino.block;

import com.itemcasino.menu.MineFieldMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.MineFieldSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class MineFieldBlockEntity extends AbstractCasinoBlockEntity {

    public MineFieldBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.MINE_FIELD.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new MineFieldSession(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.mine_field");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new MineFieldMenu(containerId, inventory, session(), player);
    }
}
