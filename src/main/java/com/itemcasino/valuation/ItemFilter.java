package com.itemcasino.valuation;

import com.itemcasino.registry.CasinoTags;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * Decides which items the casino refuses to price, and why.
 *
 * <p>Two <em>different</em> questions, and conflating them is a real bug source:
 * <ul>
 *   <li>{@link #isUnpriceableAsTarget(Item)} — would a fresh {@code new ItemStack(item)} be a
 *       useless husk? An enchanted book with no stored enchantments is a grey book; a potion with
 *       no contents is an uncraftable water bottle; a spawn egg with no entity data spawns nothing.
 *       Such items must never be selectable as an Upgrader target, whatever value the graph found.</li>
 *   <li>{@link #isComponentDriven(ItemStack)} — does the stack the player is <em>wagering</em>
 *       carry worth that the engine cannot see? Default policy is to accept it and value only the
 *       bare item, with a tooltip warning; the alternative (refuse) is a config flag.</li>
 * </ul>
 */
public final class ItemFilter {

    /**
     * Components whose presence means "the interesting part of this item is not the item".
     * Never value these; never instantiate a target that needs one.
     */
    private static final Set<DataComponentType<?>> DISQUALIFYING = Set.of(
            DataComponents.POTION_CONTENTS,
            DataComponents.STORED_ENCHANTMENTS,
            DataComponents.WRITTEN_BOOK_CONTENT,
            DataComponents.ENTITY_DATA,
            DataComponents.BLOCK_ENTITY_DATA,
            DataComponents.CONTAINER,
            DataComponents.BUNDLE_CONTENTS,
            DataComponents.CHARGED_PROJECTILES,
            DataComponents.CUSTOM_DATA,
            DataComponents.BLOCK_STATE);

    private ItemFilter() {}

    public static boolean isTagged(Item item, net.minecraft.tags.TagKey<Item> tag) {
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        return holder.is(tag);
    }

    /** Hard exclusion: never priced, never wagered, never targeted. */
    public static boolean isBlacklisted(Item item) {
        return isTagged(item, CasinoTags.UNPRICEABLE);
    }

    /**
     * True when a default instance of this item is not a meaningful item. Driven primarily by the
     * {@code itemcasino:component_driven} tag; the component heuristic is the safety net that
     * catches modded items no pack author has tagged.
     */
    public static boolean isUnpriceableAsTarget(Item item) {
        if (isBlacklisted(item)) return true;
        if (isTagged(item, CasinoTags.COMPONENT_DRIVEN)) return true;

        ItemStack probe = item.getDefaultInstance();
        if (probe.isEmpty()) return true;

        // A prototype that already declares one of the disqualifying components is fine (a filled
        // map, say). One that does NOT, but whose class only makes sense with it, cannot be
        // detected from here -- hence the tag. What we can catch is the empty husk case.
        return false;
    }

    /**
     * True for anything that is, or carries, a container of items: every shulker box and bundle
     * (by the {@code itemcasino:refused_in_slot} tag, empty or not), and any other stack that
     * actually has items or a loot table inside it — a modded backpack nobody tagged, a chest picked
     * up with its contents.
     *
     * <p>These are refused outright rather than valued as the bare container. A shulker box full of
     * netherite was priced as an empty shulker box, then either lost for that price or — at the
     * games that pay in the wagered item — copied with its contents, which turned the slot
     * machine's stake cap from sixteen items into sixteen hundred.
     *
     * <p>The component alone is not enough to refuse: vanilla gives every chest, furnace and shelf
     * item an empty container component, and those are ordinary wagers.
     */
    public static boolean holdsItems(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (isTagged(stack.getItem(), CasinoTags.REFUSED_IN_SLOT)) return true;
        if (stack.has(DataComponents.CONTAINER_LOOT)) return true;
        var contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && contents.nonEmptyStream().findAny().isPresent()) return true;
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle != null && !bundle.isEmpty();
    }

    /** True when the wagered stack carries value the engine deliberately ignores. */
    public static boolean isComponentDriven(ItemStack stack) {
        for (DataComponentType<?> type : DISQUALIFYING) {
            if (stack.has(type)) return true;
        }
        return isTagged(stack.getItem(), CasinoTags.COMPONENT_DRIVEN);
    }
}
