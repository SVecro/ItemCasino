package com.itemcasino.valuation;

import com.itemcasino.CasinoConfig;
import com.itemcasino.core.game.PayoutMath;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.registry.CasinoTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns "you won 2.5 times your wager" into concrete, legal {@link ItemStack}s.
 *
 * <p>Two rules that are easy to get wrong and expensive when you do:
 * <ul>
 *   <li>Never return a stack whose count exceeds {@code getMaxStackSize()}. An oversized stack
 *       reaching a {@code Slot} makes the client and server disagree about the inventory, which is
 *       a duplication vector, not a cosmetic bug.</li>
 *   <li>Never silently drop the fractional part of a 3:2 payout. Three diamonds at 3:2 is seven and
 *       a half diamonds, and the player counts.</li>
 * </ul>
 */
public final class PayoutResolver {

    private PayoutResolver() {}

    /** Pays {@code num/den} times the wagered stack, applying the configured rounding policy. */
    public static List<ItemStack> multiply(ItemStack wager, int num, int den,
                                           ValuationSnapshot snapshot) {
        List<ItemStack> out = new ArrayList<>(4);
        if (wager.isEmpty() || num <= 0) return out;

        PayoutMath.Rounding rounding = rounding();
        long[] split = PayoutMath.split(wager.getCount(), num, den);
        long whole = PayoutMath.applyRounding(split, rounding);
        addSplit(out, wager, whole);

        if (rounding == PayoutMath.Rounding.CHANGE && split[1] > 0) {
            long unit = StackValuator.unitValue(wager, snapshot);
            if (unit != Fixed.INF) {
                long residual = Fixed.scale(unit, split[1], split[2]);
                out.addAll(change(residual, snapshot));
            }
        }
        return out;
    }

    /** Pays a fixed number of a specific item — the Upgrader's win path. */
    public static List<ItemStack> exact(Item item, int count) {
        List<ItemStack> out = new ArrayList<>(2);
        addSplit(out, new ItemStack(item), count);
        return out;
    }

    /**
     * The most stacks one payout may be split into. High enough for the largest legal prize (an
     * Upgrader asking for 128 of an unstackable target at an output multiplier of 64 is 8 192), and
     * still a bound, so a pathological multiplier cannot allocate forever.
     */
    public static final int MAX_STACKS = 16_384;

    /**
     * Splits {@code count} copies of a prototype into stacks that respect the item's stack limit.
     * Every entry is a fresh copy: sharing an {@link ItemStack} reference between the escrow and
     * the payout is the oldest duplication bug in the book.
     *
     * @return how many items did <em>not</em> fit under {@link #MAX_STACKS}; zero for every legal
     *         payout. It used to stop at 512 stacks and say nothing, which silently kept most of a
     *         triple-star win on sixteen ender pearls.
     */
    public static long addSplit(List<ItemStack> out, ItemStack prototype, long count) {
        if (prototype.isEmpty() || count <= 0) return 0;
        int max = Math.max(1, prototype.getMaxStackSize());
        long remaining = count;
        int stacks = 0;
        while (remaining > 0 && stacks++ < MAX_STACKS) {
            int take = (int) Math.min(max, remaining);
            ItemStack copy = prototype.copy();
            copy.setCount(take);
            out.add(copy);
            remaining -= take;
        }
        if (remaining > 0) {
            com.itemcasino.ItemCasino.LOGGER.error("Payout of {} x {} exceeds {} stacks; {} not paid",
                    count, prototype.getItem(), MAX_STACKS, remaining);
        }
        return remaining;
    }

    /**
     * The payout as the client is shown it: one entry per distinct item, count summed.
     *
     * <p>The payload list is capped (an uncapped list decoder is an out-of-memory vector), and a
     * triple-star win on sixteen ender pearls is eight hundred stacks — which used to overflow the
     * cap of 64 inside the network encoder and disconnect the winner and everyone watching. Nothing
     * on the client reads more than "is it empty", so the summary loses nothing it needs. The
     * server-side buffer keeps its legal stacks.
     */
    public static List<ItemStack> summarise(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>(4);
        outer:
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            for (ItemStack seen : out) {
                if (ItemStack.isSameItemSameComponents(seen, stack)) {
                    long sum = (long) seen.getCount() + stack.getCount();
                    seen.setCount((int) Math.min(Integer.MAX_VALUE, sum));
                    continue outer;
                }
            }
            if (out.size() >= SUMMARY_LIMIT) continue;
            out.add(stack.copy());
        }
        return out;
    }

    /** Must stay at or under the list cap of the payloads that carry a summary. */
    public static final int SUMMARY_LIMIT = 64;

    /**
     * Pays a sub-item remainder with the cheapest denominations available, largest first.
     * The tag is authored by the pack; the values come from the same engine that priced the wager,
     * so the change is always worth at most the remainder and never more.
     */
    private static List<ItemStack> change(long residualValue, ValuationSnapshot snapshot) {
        List<ItemStack> out = new ArrayList<>(2);
        if (residualValue <= 0) return out;

        List<Item> denominations = new ArrayList<>(8);
        for (int i = 0; i < snapshot.itemCount(); i++) {
            Item item = snapshot.itemAt(i);
            if (ItemFilter.isTagged(item, CasinoTags.CHANGE_CURRENCY)
                    && snapshot.valueAt(i) != Fixed.INF && snapshot.valueAt(i) > 0) {
                denominations.add(item);
            }
        }
        denominations.sort(Comparator.comparingLong((Item i) -> snapshot.value(i)).reversed());

        long left = residualValue;
        for (Item denomination : denominations) {
            long unit = snapshot.value(denomination);
            if (unit <= 0 || unit == Fixed.INF || unit > left) continue;
            long count = left / unit;
            if (count <= 0) continue;
            count = Math.min(count, 256);              // keep change sane
            addSplit(out, new ItemStack(denomination), count);
            left -= unit * count;
            if (left <= 0) break;
        }
        return out;
    }

    private static PayoutMath.Rounding rounding() {
        String configured = CasinoConfig.SERVER.rounding.get();
        try {
            return PayoutMath.Rounding.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PayoutMath.Rounding.CHANGE;
        }
    }
}
