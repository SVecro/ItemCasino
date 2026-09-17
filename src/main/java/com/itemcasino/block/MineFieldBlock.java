package com.itemcasino.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/** Five by five, and somewhere under it, the mines. */
public class MineFieldBlock extends AbstractCasinoBlock {

    public static final MapCodec<MineFieldBlock> CODEC = simpleCodec(MineFieldBlock::new);

    public MineFieldBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractCasinoBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MineFieldBlockEntity(pos, state);
    }
}
