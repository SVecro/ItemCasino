package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.itemcasino.block.BlackjackTableBlockEntity;
import com.itemcasino.block.CoinFlipBlockEntity;
import com.itemcasino.block.MineFieldBlockEntity;
import com.itemcasino.block.SlotMachineBlockEntity;
import com.itemcasino.block.VaultBlockEntity;
import com.itemcasino.block.DiceBlockEntity;
import com.itemcasino.block.UpgraderBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> REGISTER =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ItemCasino.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<UpgraderBlockEntity>> UPGRADER =
            REGISTER.register("upgrader", () -> new BlockEntityType<>(
                    UpgraderBlockEntity::new, CasinoBlocks.UPGRADER.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DiceBlockEntity>> DICE =
            REGISTER.register("predict_the_dice", () -> new BlockEntityType<>(
                    DiceBlockEntity::new, CasinoBlocks.DICE.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlackjackTableBlockEntity>> BLACKJACK_TABLE =
            REGISTER.register("blackjack_table", () -> new BlockEntityType<>(
                    BlackjackTableBlockEntity::new, CasinoBlocks.BLACKJACK_TABLE.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CoinFlipBlockEntity>> COIN_FLIP =
            REGISTER.register("coin_flip", () -> new BlockEntityType<>(
                    CoinFlipBlockEntity::new, CasinoBlocks.COIN_FLIP.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SlotMachineBlockEntity>> SLOT_MACHINE =
            REGISTER.register("slot_machine", () -> new BlockEntityType<>(
                    SlotMachineBlockEntity::new, CasinoBlocks.SLOT_MACHINE.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VaultBlockEntity>> VAULT =
            REGISTER.register("vault", () -> new BlockEntityType<>(
                    VaultBlockEntity::new, CasinoBlocks.VAULT.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MineFieldBlockEntity>> MINE_FIELD =
            REGISTER.register("mine_field", () -> new BlockEntityType<>(
                    MineFieldBlockEntity::new, CasinoBlocks.MINE_FIELD.get()));

    // Predict the Dice took Double or Nothing's place. The alias loads the table data saved in every
    // chunk as the new table instead of dropping it.
    static {
        REGISTER.addAlias(ItemCasino.id("double_or_nothing"), ItemCasino.id("predict_the_dice"));
    }

    private CasinoBlockEntities() {}
}
