package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.itemcasino.block.BlackjackTableBlock;
import com.itemcasino.block.CashierBlock;
import com.itemcasino.block.CoinFlipBlock;
import com.itemcasino.block.MineFieldBlock;
import com.itemcasino.block.SlotMachineBlock;
import com.itemcasino.block.VaultBlock;
import com.itemcasino.block.DiceBlock;
import com.itemcasino.block.UpgraderBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoBlocks {

    public static final DeferredRegister.Blocks REGISTER =
            DeferredRegister.createBlocks(ItemCasino.MOD_ID);

    private static BlockBehaviour.Properties tableProperties(MapColor colour) {
        return BlockBehaviour.Properties.of()
                .mapColor(colour)
                .strength(3.5F, 12.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL)
                .noOcclusion();
    }

    public static final DeferredBlock<UpgraderBlock> UPGRADER = REGISTER.registerBlock(
            "upgrader", UpgraderBlock::new, () -> tableProperties(MapColor.GOLD));

    public static final DeferredBlock<DiceBlock> DICE = REGISTER.registerBlock(
            "predict_the_dice", DiceBlock::new, () -> tableProperties(MapColor.COLOR_RED));

    public static final DeferredBlock<BlackjackTableBlock> BLACKJACK_TABLE = REGISTER.registerBlock(
            "blackjack_table", BlackjackTableBlock::new, () -> tableProperties(MapColor.COLOR_GREEN));

    public static final DeferredBlock<CoinFlipBlock> COIN_FLIP = REGISTER.registerBlock(
            "coin_flip", CoinFlipBlock::new, () -> tableProperties(MapColor.COLOR_BLUE));

    public static final DeferredBlock<SlotMachineBlock> SLOT_MACHINE = REGISTER.registerBlock(
            "slot_machine", SlotMachineBlock::new, () -> tableProperties(MapColor.TERRACOTTA_PURPLE));

    public static final DeferredBlock<VaultBlock> VAULT = REGISTER.registerBlock(
            "vault", VaultBlock::new, () -> tableProperties(MapColor.COLOR_BLACK));

    public static final DeferredBlock<CashierBlock> CASHIER = REGISTER.registerBlock(
            "cashier", CashierBlock::new, () -> tableProperties(MapColor.GOLD));

    public static final DeferredBlock<MineFieldBlock> MINE_FIELD = REGISTER.registerBlock(
            "mine_field", MineFieldBlock::new, () -> tableProperties(MapColor.COLOR_ORANGE));

    /**
     * The part every casino machine is built around, and the one ingredient they all share. It does
     * nothing on its own: it is a crafting component you can also build with, so a casino floor can
     * show its workings.
     */
    public static final DeferredBlock<Block> GAME_CORE = REGISTER.registerSimpleBlock("game_core",
            () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.5F, 12.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL));


    // Predict the Dice took Double or Nothing's place. The alias turns every table already placed in
    // a world, and every one in an inventory, into the new one instead of letting it vanish.
    static {
        REGISTER.addAlias(ItemCasino.id("double_or_nothing"), ItemCasino.id("predict_the_dice"));
    }

    private CasinoBlocks() {}

    /** Every casino table, used by the block-entity type and by the break guard. */
    public static Block[] all() {
        return new Block[] { UPGRADER.get(), DICE.get(), BLACKJACK_TABLE.get(),
                COIN_FLIP.get(), SLOT_MACHINE.get(), VAULT.get(), MINE_FIELD.get() };
    }
}
