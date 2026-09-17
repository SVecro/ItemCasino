package com.itemcasino.client;

import com.itemcasino.core.value.Fixed;
import com.itemcasino.network.s2c.S2CValueTable;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The client's copy of the value table.
 *
 * <p><strong>Advisory only.</strong> It exists so the target picker can list and sort a thousand
 * items without a round trip each. Nothing that decides an outcome may read it: the odds beside the
 * confirm button come from {@code S2COddsQuote}, and the roll happens on the server. If you ever
 * find yourself calling this from anything but rendering or sorting, that is the bug.
 */
public final class ClientValueCache {

    private static final Reference2LongOpenHashMap<Item> VALUES = new Reference2LongOpenHashMap<>();
    private static List<Item> sorted = List.of();

    static {
        VALUES.defaultReturnValue(Fixed.INF);
    }

    private ClientValueCache() {}

    public static void accept(S2CValueTable table) {
        VALUES.clear();
        List<Item> items = new ArrayList<>(table.entries().size());
        for (S2CValueTable.Entry entry : table.entries()) {
            Item item = BuiltInRegistries.ITEM.byId(entry.itemId());
            if (item == null) continue;
            VALUES.put(item, entry.value());
            items.add(item);
        }
        items.sort(Comparator.comparingLong(VALUES::getLong));
        sorted = List.copyOf(items);
    }

    /** The table belongs to one server. Carrying it to the next one would show wrong numbers. */
    public static void clear() {
        VALUES.clear();
        sorted = List.of();
    }

    public static boolean isReady() { return !sorted.isEmpty(); }

    public static long value(Item item) { return VALUES.getLong(item); }

    /** Every selectable target, cheapest first. */
    public static List<Item> targets() { return sorted; }

    /** Filtered by a lower-cased search string against the display name and the registry path. */
    public static List<Item> search(String query) {
        if (query == null || query.isBlank()) return sorted;
        String needle = query.toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>(64);
        for (Item item : sorted) {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (path.contains(needle)
                    || item.getDefaultInstance().getHoverName().getString()
                        .toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(item);
            }
        }
        return out;
    }
}
