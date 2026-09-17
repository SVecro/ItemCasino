package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.itemcasino.item.PocketCasinoItem;
import com.itemcasino.session.GameKind;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoItems {

    public static final DeferredRegister.Items REGISTER =
            DeferredRegister.createItems(com.itemcasino.ItemCasino.MOD_ID);

    // --- the tables -----------------------------------------------------------
    public static final DeferredItem<BlockItem> UPGRADER =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.UPGRADER);

    public static final DeferredItem<BlockItem> DICE =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.DICE);

    public static final DeferredItem<BlockItem> BLACKJACK_TABLE =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.BLACKJACK_TABLE);

    public static final DeferredItem<BlockItem> COIN_FLIP =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.COIN_FLIP);

    public static final DeferredItem<BlockItem> SLOT_MACHINE =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.SLOT_MACHINE);

    public static final DeferredItem<BlockItem> VAULT =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.VAULT);

    public static final DeferredItem<BlockItem> MINE_FIELD =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.MINE_FIELD);

    public static final DeferredItem<BlockItem> CASHIER =
            REGISTER.registerSimpleBlockItem(CasinoBlocks.CASHIER);

    /** A card holding casino chips. One per stack: two balances cannot share a slot. */
    public static final DeferredItem<Item> CHIP_CARD = REGISTER.registerItem(
            "chip_card", Item::new, properties -> properties.stacksTo(1).rarity(Rarity.UNCOMMON));

    // --- the pocket editions --------------------------------------------------
    // One per game rather than one item with a selector: a player should be able to see at a
    // glance what is in their hotbar, and three cheap items read better than one modal one.
    public static final DeferredItem<PocketCasinoItem> POCKET_UPGRADER = REGISTER.registerItem(
            "pocket_upgrader", properties -> new PocketCasinoItem(properties, GameKind.UPGRADER),
            pocketProperties());

    public static final DeferredItem<PocketCasinoItem> POCKET_DICE = REGISTER.registerItem(
            "pocket_dice",
            properties -> new PocketCasinoItem(properties, GameKind.DICE),
            pocketProperties());

    public static final DeferredItem<PocketCasinoItem> POCKET_BLACKJACK = REGISTER.registerItem(
            "pocket_blackjack", properties -> new PocketCasinoItem(properties, GameKind.BLACKJACK),
            pocketProperties());

    private static Item.Properties pocketProperties() {
        return new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON);
    }

    // Predict the Dice took Double or Nothing's place. The alias turns every table already placed in
    // a world, and every one in an inventory, into the new one instead of letting it vanish.
    static {
        REGISTER.addAlias(ItemCasino.id("double_or_nothing"), ItemCasino.id("predict_the_dice"));
        REGISTER.addAlias(ItemCasino.id("pocket_double_or_nothing"), ItemCasino.id("pocket_dice"));
    }

    private CasinoItems() {}
}
