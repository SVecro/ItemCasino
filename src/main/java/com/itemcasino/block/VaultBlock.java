package com.itemcasino.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/** Where the house keeps what everybody lost. */
public class VaultBlock extends AbstractCasinoBlock {

    public static final MapCodec<VaultBlock> CODEC = simpleCodec(VaultBlock::new);

    public VaultBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractCasinoBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VaultBlockEntity(pos, state);
    }
}
