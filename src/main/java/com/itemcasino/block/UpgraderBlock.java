package com.itemcasino.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class UpgraderBlock extends AbstractCasinoBlock {

    public static final MapCodec<UpgraderBlock> CODEC = simpleCodec(UpgraderBlock::new);

    public UpgraderBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractCasinoBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new UpgraderBlockEntity(pos, state);
    }
}
