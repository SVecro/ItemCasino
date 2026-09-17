package com.itemcasino.valuation;

import com.itemcasino.CasinoConfig;
import com.itemcasino.core.value.Fixed;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

/**
 * The guess for items the base values cannot reach.
 *
 * <p>Order matters: an explicit base value beats a recipe, a recipe beats this guess, and the guess
 * beats nothing. The guess is deliberately low (one point for a common item, the price of a
 * cobblestone) and anything priced by it is marked <em>estimated</em>: it may be wagered at that
 * price but never offered as a target, so a wrong guess can only ever cost the player who chose to
 * wager the item, never the house. It used to be sixteen points and targetable, which put
 * netherrack and rotten flesh above an iron ingot.
 */
public final class FallbackValuator {

    private FallbackValuator() {}

    public static long rarityValue(Item item) {
        if (!CasinoConfig.SERVER.estimateUnknownItems.get()) return Fixed.INF;

        ItemStack probe = item.getDefaultInstance();
        if (probe.isEmpty()) return Fixed.INF;

        Rarity rarity = probe.getOrDefault(DataComponents.RARITY, Rarity.COMMON);
        long points = switch (rarity) {
            case UNCOMMON -> CasinoConfig.SERVER.rarityUncommon.get();
            case RARE -> CasinoConfig.SERVER.rarityRare.get();
            case EPIC -> CasinoConfig.SERVER.rarityEpic.get();
            default -> CasinoConfig.SERVER.rarityCommon.get();
        };
        return points <= 0 ? Fixed.INF : Fixed.ofPoints(points);
    }
}
