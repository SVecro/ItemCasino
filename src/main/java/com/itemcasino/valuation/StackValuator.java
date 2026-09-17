package com.itemcasino.valuation;

import com.itemcasino.CasinoConfig;
import com.itemcasino.core.value.Fixed;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Prices a concrete {@link ItemStack}, as opposed to an item type.
 *
 * <p>Always evaluated on the server, against the stack actually held in escrow — never against
 * anything the client said. This is where a player trying to wager a one-hit-from-breaking
 * netherite pickaxe as if it were new gets priced correctly.
 */
public final class StackValuator {

    private StackValuator() {}

    /** Value of the whole stack (unit value multiplied by the count). */
    public static long value(ItemStack stack, ValuationSnapshot snapshot) {
        long unit = unitValue(stack, snapshot);
        if (unit == Fixed.INF) return Fixed.INF;
        return Fixed.mul(unit, stack.getCount());
    }

    /** Value of a single item of this stack, including its durability and enchantments. */
    public static long unitValue(ItemStack stack, ValuationSnapshot snapshot) {
        if (stack.isEmpty()) return Fixed.INF;
        // A chip card is money, not merchandise: it is bet as chips and never priced as an item,
        // or a card worth a thousand chips would go into the pot as "one card".
        if (com.itemcasino.chips.ChipCards.isCard(stack)) return Fixed.INF;

        long base = snapshot.value(stack.getItem());
        if (base == Fixed.INF) return Fixed.INF;

        Integer maxDamage = stack.get(DataComponents.MAX_DAMAGE);
        if (maxDamage != null && maxDamage > 0) {
            int damage = stack.getOrDefault(DataComponents.DAMAGE, 0);
            double remaining = clamp01(1.0D - (double) damage / (double) maxDamage);
            double floor = CasinoConfig.SERVER.durabilityFloor.get();
            double factor = floor + (1.0D - floor) * remaining;
            base = (long) (base * factor);
            if (base < Fixed.MIN_POSITIVE) base = Fixed.MIN_POSITIVE;
        }

        if (CasinoConfig.SERVER.valueEnchantments.get()) {
            ItemEnchantments enchantments =
                    stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            long perLevel = Fixed.ofPoints(CasinoConfig.SERVER.enchantLevelValue.get());
            for (var entry : enchantments.entrySet()) {
                base = Fixed.add(base, Fixed.mul(perLevel, entry.getIntValue()));
            }
        }
        return base;
    }

    /** Why a stack cannot be wagered, or {@code null} when it can. */
    public static Rejection reject(ItemStack stack, ValuationSnapshot snapshot) {
        if (stack.isEmpty()) return Rejection.EMPTY;
        if (!snapshot.isReady()) return Rejection.NOT_READY;
        if (ItemFilter.isBlacklisted(stack.getItem())) return Rejection.BLACKLISTED;
        if (ItemFilter.holdsItems(stack)) return Rejection.CONTAINER;
        if (CasinoConfig.SERVER.refuseComponentDrivenInput.get() && ItemFilter.isComponentDriven(stack)) {
            return Rejection.COMPONENT_DRIVEN;
        }
        long value = value(stack, snapshot);
        if (value == Fixed.INF) return Rejection.UNPRICED;
        if (value < Fixed.ofPoints(CasinoConfig.SERVER.minWagerValuePoints.get())) {
            return Rejection.TOO_CHEAP;
        }
        return null;
    }

    public enum Rejection {
        EMPTY("itemcasino.reject.empty"),
        NOT_READY("itemcasino.reject.not_ready"),
        BLACKLISTED("itemcasino.reject.blacklisted"),
        CONTAINER("itemcasino.reject.container"),
        COMPONENT_DRIVEN("itemcasino.reject.component_driven"),
        UNPRICED("itemcasino.reject.unpriced"),
        TOO_CHEAP("itemcasino.reject.too_cheap");

        private final String key;

        Rejection(String key) { this.key = key; }

        public String translationKey() { return key; }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
