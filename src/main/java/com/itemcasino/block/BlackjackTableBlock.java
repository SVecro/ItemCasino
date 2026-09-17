package com.itemcasino.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class BlackjackTableBlock extends AbstractCasinoBlock {

    public static final MapCodec<BlackjackTableBlock> CODEC = simpleCodec(BlackjackTableBlock::new);

    public BlackjackTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractCasinoBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlackjackTableBlockEntity(pos, state);
    }
}
