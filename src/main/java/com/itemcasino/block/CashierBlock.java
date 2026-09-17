package com.itemcasino.block;

import com.itemcasino.menu.CashierMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The cashier: items in, chips out, and back.
 *
 * <p>It holds nothing. Like a crafting table, the two slots belong to the menu of whoever is using
 * it and are handed back when the screen closes, so there is nothing to lose when the block is
 * broken and nothing for a second player to take.
 */
public class CashierBlock extends Block {

    public static final MapCodec<CashierBlock> CODEC = simpleCodec(CashierBlock::new);

    public CashierBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.CONSUME;
        CashierMenu.Rates rates = CashierMenu.Rates.current();
        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, who) -> new CashierMenu(containerId, inventory,
                        ContainerLevelAccess.create(level, pos), rates),
                Component.translatable("container.itemcasino.cashier")), rates::write);
        return InteractionResult.CONSUME;
    }
}
