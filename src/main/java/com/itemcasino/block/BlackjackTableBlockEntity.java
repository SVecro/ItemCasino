package com.itemcasino.block;

import com.itemcasino.menu.BlackjackMenu;
import com.itemcasino.registry.CasinoBlockEntities;
import com.itemcasino.session.BlackjackSession;
import com.itemcasino.session.CasinoSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class BlackjackTableBlockEntity extends AbstractCasinoBlockEntity {

    public BlackjackTableBlockEntity(BlockPos pos, BlockState state) {
        super(CasinoBlockEntities.BLACKJACK_TABLE.get(), pos, state);
    }

    @Override
    protected CasinoSession createSession() {
        return new BlackjackSession(this, com.itemcasino.core.game.blackjack.BlackjackTable.MAX_SEATS);
    }

    public BlackjackSession blackjack() {
        return (BlackjackSession) session();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.itemcasino.blackjack_table");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new BlackjackMenu(containerId, inventory, session(), player);
    }
}
