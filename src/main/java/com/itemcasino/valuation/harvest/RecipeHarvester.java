package com.itemcasino.valuation.harvest;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.core.value.ValueGraph;
import com.itemcasino.registry.CasinoDataMaps;
import com.itemcasino.valuation.BaseValue;
import com.itemcasino.valuation.FallbackValuator;
import com.itemcasino.valuation.ItemFilter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Turns the server's loaded recipes into a {@link ValueGraph}.
 *
 * <p>Runs on the server thread, once per reload. It is deliberately <em>type agnostic</em>: since
 * 1.21.2 every recipe exposes its inputs through {@link Recipe#placementInfo()} and its output
 * through {@link Recipe#display()}, so there is not a single {@code instanceof ShapedRecipe} cast
 * in here and modded recipe types come along for free.
 *
 * <p>Recipes that cannot be expressed this way exclude themselves: the {@code crafting_special_*}
 * family and anything that only edits data components return {@link PlacementInfo#NOT_PLACEABLE}
 * and/or an empty {@code display()}. That is the correct behaviour — they are unbounded, not free.
 */
public final class RecipeHarvester {

    /** Exactly the recipe types the design promises to walk, plus anything modded that reuses them. */
    private static final List<RecipeType<?>> HARVESTED = List.of(
            RecipeType.CRAFTING,          // shaped, shapeless and transmute
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING);         // smithing_transform and smithing_trim

    /**
     * Everything the off-thread solver needs, with no live Minecraft state in it.
     *
     * @param targetable per-item: may this be chosen as an Upgrader target?
     */
    public record Harvest(ValueGraph graph, Reference2IntOpenHashMap<Item> index, Item[] items,
                          long[] seed, boolean[] pinned, long[] fallback, boolean[] targetable,
                          int recipesSeen, int recipesSkipped, long tableHash) {}

    private RecipeHarvester() {}

    public static Harvest harvest(ServerLevel level) {
        final long t0 = System.nanoTime();

        ValueGraph.Builder builder = new ValueGraph.Builder();
        Reference2IntOpenHashMap<Item> index = new Reference2IntOpenHashMap<>();
        index.defaultReturnValue(-1);
        List<Item> itemList = new ArrayList<>(2048);

        // Intern every registered item first, in registry order, so dense indices are stable and
        // every item (recipe or not) has a slot for its fallback value and its targetable flag.
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            index.put(item, builder.intern(item));
            itemList.add(item);
        }
        Item[] items = itemList.toArray(new Item[0]);
        int n = items.length;

        long[] seed = new long[n];
        long[] fallback = new long[n];
        boolean[] pinned = new boolean[n];
        boolean[] targetable = new boolean[n];
        Arrays.fill(seed, Fixed.INF);
        Arrays.fill(fallback, Fixed.INF);

        for (int i = 0; i < n; i++) {
            Item item = items[i];
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
            BaseValue base = holder.getData(CasinoDataMaps.BASE_VALUE);
            if (base != null && base.value() > 0) {
                seed[i] = Fixed.ofPoints(base.value());
                pinned[i] = base.fixed();
            }
            fallback[i] = FallbackValuator.rarityValue(item);
            targetable[i] = !ItemFilter.isUnpriceableAsTarget(item);
        }
        applyConfigOverrides(index, seed, pinned);

        final int maxOptions = CasinoConfig.SERVER.maxIngredientOptions.get();
        final long fuelCost = Fixed.ofPoints(CasinoConfig.SERVER.smeltingFuelCost.get());

        RecipeManager recipes = level.recipeAccess();
        ContextMap ctx = SlotDisplayContext.fromLevel(level);
        int seen = 0, skipped = 0;
        long hash = 1125899906842597L;

        for (RecipeType<?> type : HARVESTED) {
            long surcharge = isCooking(type) ? fuelCost : 0L;
            for (RecipeHolder<?> holder : byType(recipes, type)) {
                seen++;
                Recipe<?> recipe = holder.value();

                // --- inputs -------------------------------------------------------------
                PlacementInfo placement;
                try {
                    placement = recipe.placementInfo();
                } catch (RuntimeException e) {          // a broken modded recipe must not kill the reload
                    skipped++;
                    continue;
                }
                if (placement.isImpossibleToPlace()) { skipped++; continue; }
                List<Ingredient> ingredients = placement.ingredients();
                if (ingredients.isEmpty()) { skipped++; continue; }

                // --- output -------------------------------------------------------------
                List<RecipeDisplay> displays = recipe.display();
                if (displays.isEmpty()) { skipped++; continue; }
                ItemStack result;
                try {
                    result = displays.getFirst().result().resolveForFirstStack(ctx);
                } catch (RuntimeException e) {
                    skipped++;
                    continue;
                }
                if (result.isEmpty() || result.getCount() <= 0) { skipped++; continue; }
                Item output = result.getItem();
                if (ItemFilter.isBlacklisted(output)) { skipped++; continue; }
                int outputIndex = index.getInt(output);
                if (outputIndex < 0) { skipped++; continue; }

                // --- flatten ------------------------------------------------------------
                List<int[]> slots = new ArrayList<>(ingredients.size());
                IntArrayList remainderItems = new IntArrayList();
                boolean usable = true;

                for (Ingredient ingredient : ingredients) {
                    if (isComponentSensitive(ingredient)) { usable = false; break; }

                    int[] options = ingredient.items()
                            .limit(maxOptions)
                            .mapToInt(h -> index.getInt(h.value()))
                            .filter(i -> i >= 0)
                            .toArray();
                    if (options.length == 0) { usable = false; break; }
                    slots.add(options);

                    int remainder = sharedCraftingRemainder(ingredient, index, maxOptions);
                    if (remainder >= 0) remainderItems.add(remainder);
                }
                if (!usable) { skipped++; continue; }

                Identifier id = holder.id().identifier();
                hash = hash * 31 + id.hashCode();
                hash = hash * 31 + result.getCount();

                builder.addConversion(id, outputIndex, result.getCount(), slots,
                        surcharge, 0L, remainderItems.toIntArray());
            }
        }

        ValueGraph graph = builder.build();
        int totalSkipped = skipped + builder.skippedConversions();
        ItemCasino.LOGGER.debug("Harvested {} recipes ({} skipped) into {} conversions over {} items in {} ms",
                seen, totalSkipped, graph.conversionCount, n, (System.nanoTime() - t0) / 1_000_000);

        return new Harvest(graph, index, items, seed, pinned, fallback, targetable,
                seen, totalSkipped, hash);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Collection<RecipeHolder<?>> byType(RecipeManager manager, RecipeType<?> type) {
        return (Collection) manager.recipeMap().byType((RecipeType) type);
    }

    private static boolean isCooking(RecipeType<?> type) {
        return type == RecipeType.SMELTING || type == RecipeType.BLASTING
                || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING;
    }

    /**
     * A NeoForge custom ingredient that matches on data components cannot be reduced to "the
     * cheapest item id that could satisfy it" — doing so would price a Sharpness V sword as a plain
     * one. Such recipes are dropped rather than mispriced.
     */
    private static boolean isComponentSensitive(Ingredient ingredient) {
        var custom = ingredient.getCustomIngredient();
        return custom != null && !custom.isSimple();
    }

    /**
     * @return the dense index of the item this ingredient hands back (an empty bucket from a milk
     *         bucket, a bottle from a potion), or -1 when the options disagree or return nothing.
     *         Requiring agreement across options keeps the credit honest for tag ingredients.
     */
    private static int sharedCraftingRemainder(Ingredient ingredient,
                                               Reference2IntOpenHashMap<Item> index, int maxOptions) {
        int shared = -2;   // -2 = nothing seen yet, -1 = disagreement
        long examined = 0;
        for (Holder<Item> holder : ingredient.items().limit(maxOptions).toList()) {
            if (++examined > maxOptions) break;
            ItemStack remainder;
            try {
                remainder = new ItemStack(holder).getCraftingRemainder();
            } catch (RuntimeException e) {
                return -1;
            }
            int id = remainder.isEmpty() ? -1 : index.getInt(remainder.getItem());
            if (shared == -2) shared = id;
            else if (shared != id) return -1;
        }
        return shared < 0 ? -1 : shared;
    }

    private static void applyConfigOverrides(Reference2IntOpenHashMap<Item> index,
                                             long[] seed, boolean[] pinned) {
        for (String entry : CasinoConfig.SERVER.valueOverrides.get()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String idPart = entry.substring(0, eq).trim();
            String valuePart = entry.substring(eq + 1).trim();
            boolean pin = valuePart.endsWith("!");
            if (pin) valuePart = valuePart.substring(0, valuePart.length() - 1).trim();

            Identifier id = Identifier.tryParse(idPart);
            if (id == null) { warnBadOverride(entry); continue; }
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null || item == Items.AIR) { warnBadOverride(entry); continue; }
            int slot = index.getInt(item);
            if (slot < 0) { warnBadOverride(entry); continue; }

            try {
                long points = Long.parseLong(valuePart);
                seed[slot] = points <= 0 ? Fixed.INF : Fixed.ofPoints(points);
                pinned[slot] = pin && points > 0;
            } catch (NumberFormatException e) {
                warnBadOverride(entry);
            }
        }
    }

    private static void warnBadOverride(String entry) {
        ItemCasino.LOGGER.warn("Ignoring malformed value override '{}' "
                + "(expected 'namespace:item=points' or 'namespace:item=points!')", entry);
    }
}
