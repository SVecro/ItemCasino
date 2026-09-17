package com.itemcasino.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/** The duelling table: two chairs, no house. */
public class CoinFlipBlock extends AbstractCasinoBlock {

    public static final MapCodec<CoinFlipBlock> CODEC = simpleCodec(CoinFlipBlock::new);

    public CoinFlipBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractCasinoBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoinFlipBlockEntity(pos, state);
    }
}
