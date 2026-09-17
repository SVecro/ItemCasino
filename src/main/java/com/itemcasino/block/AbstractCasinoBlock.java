package com.itemcasino.block;

import com.itemcasino.registry.CasinoBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Shared behaviour for every casino table: open the menu, tick the session, render as a model. */
public abstract class AbstractCasinoBlock extends BaseEntityBlock {

    protected AbstractCasinoBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // BaseEntityBlock defaults to INVISIBLE, which would make the table disappear.
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.CONSUME;
        if (!(level.getBlockEntity(pos) instanceof AbstractCasinoBlockEntity table)) {
            return InteractionResult.CONSUME;
        }

        // Anyone may pull up a chair. The first arrival takes the seat and is the only one who
        // can touch the slot; everyone else watches the same wheel and the same cards.
        table.openFor(serverPlayer);

        // The leading boolean distinguishes a table from a pocket device, so one menu type and one
        // screen serve both.
        serverPlayer.openMenu(table, buffer -> {
            buffer.writeBoolean(false);
            buffer.writeBlockPos(pos);
        });
        // Only once the menu is open: what the session sends a returning viewer has to be addressed
        // to that menu's container id.
        if (serverPlayer.containerMenu instanceof com.itemcasino.menu.AbstractCasinoMenu menu
                && menu.session() == table.session()) {
            table.session().onViewerOpened(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        // Every table's block entity is a casino table, so one ticker serves them all; a table added
        // later does not need a line of its own here.
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof AbstractCasinoBlockEntity table) {
                AbstractCasinoBlockEntity.serverTick(tickLevel, tickPos, tickState, table);
            }
        };
    }

}
