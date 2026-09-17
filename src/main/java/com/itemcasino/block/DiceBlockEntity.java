package com.itemcasino.block;

import com.itemcasino.menu.DiceMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.DiceSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class DiceBlockEntity extends AbstractCasinoBlockEntity {

    public DiceBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.DICE.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new DiceSession(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.predict_the_dice");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new DiceMenu(containerId, inventory, session(), player);
    }
}
