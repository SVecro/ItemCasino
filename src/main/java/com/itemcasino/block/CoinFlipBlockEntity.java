package com.itemcasino.block;

import com.itemcasino.menu.CoinFlipMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.CoinFlipSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class CoinFlipBlockEntity extends AbstractCasinoBlockEntity {

    public CoinFlipBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.COIN_FLIP.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new CoinFlipSession(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.coin_flip");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new CoinFlipMenu(containerId, inventory, session(), player);
    }
}
