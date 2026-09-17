package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class CasinoTags {

    /** Items that can never be wagered or targeted, whatever their computed value. */
    public static final TagKey<Item> UNPRICEABLE =
            TagKey.create(Registries.ITEM, ItemCasino.id("unpriceable"));

    /**
     * Items whose worth lives entirely in their data components. A default instance of one of these
     * is a useless husk (a grey enchanted book, an uncraftable water bottle, an inert spawn egg),
     * so they must never appear as an Upgrader target.
     */
    public static final TagKey<Item> COMPONENT_DRIVEN =
            TagKey.create(Registries.ITEM, ItemCasino.id("component_driven"));

    /** Small-denomination items used to pay a fractional payout remainder in CHANGE mode. */
    public static final TagKey<Item> CHANGE_CURRENCY =
            TagKey.create(Registries.ITEM, ItemCasino.id("change_currency"));

    /**
     * Items that may never be put in a casino slot at all: shulker boxes, bundles, and anything a
     * pack adds that carries other items. Checked by the slot itself, and again whenever a wager is
     * valued, alongside a check for the container components so an untagged modded backpack is
     * caught too.
     */
    public static final TagKey<Item> REFUSED_IN_SLOT =
            TagKey.create(Registries.ITEM, ItemCasino.id("refused_in_slot"));

    /**
     * What the Cashier takes on deposit: by default the currencies it pays out and their blocks.
     * Anything else is played at the tables, where the house keeps its edge; a cashier that took
     * every item at its full value turned every farm into a diamond mine at 98 %.
     */
    public static final TagKey<Item> CASHIER_ACCEPTS =
            TagKey.create(Registries.ITEM, ItemCasino.id("cashier_accepts"));

    /**
     * Items that are never offered as an Upgrader target, whatever their value. Empty in the mod; a
     * server or a pack fills it (a dragon egg, a nether star...) when it wants some items to stay out
     * of reach of the wheel.
     */
    public static final TagKey<Item> NOT_A_TARGET =
            TagKey.create(Registries.ITEM, ItemCasino.id("not_a_target"));

    private CasinoTags() {}

}
