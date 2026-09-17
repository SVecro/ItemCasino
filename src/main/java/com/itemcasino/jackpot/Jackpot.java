package com.itemcasino.jackpot;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.valuation.PayoutResolver;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The house's losses, kept where everyone can see them.
 *
 * <h2>What it is</h2>
 * Every item a player loses at a table is banked here instead of ceasing to exist, and sits in one
 * server-wide pot until somebody takes the lot. It turns the casino from a hole that items fall
 * into to a thing that redistributes them — the same items, in fewer hands, less often.
 *
 * <h2>Why a hoard of counts rather than a chest of stacks</h2>
 * A busy server loses thousands of stacks. Storing them as stacks would grow the save file without
 * bound and take minutes to serialise; storing each distinct item once, with a {@code long} count,
 * does not. Two items only share an entry when they are the same item <em>with the same
 * components</em>, so an enchanted pickaxe is never quietly merged with a plain one.
 *
 * <h2>The one thing it will not do</h2>
 * Past {@link #MAX_ENTRIES} distinct items it stops banking new ones rather than evicting old ones.
 * Evicting would mean deleting somebody's items to make room for somebody else's, and the honest
 * failure here is the old behaviour: the item is consumed, as it was before the pot existed.
 */
public class Jackpot extends SavedData {

    /** Distinct items the pot will hold. Beyond this, new kinds are consumed rather than banked. */
    public static final int MAX_ENTRIES = 128;

    private static final String FILE_ID = "itemcasino_jackpot";

    /** One kind of item and how many of it the pot holds. */
    public record Hoard(ItemStack prototype, long count) {
        public static final Codec<Hoard> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.SINGLE_ITEM_CODEC.fieldOf("item").forGetter(Hoard::prototype),
                Codec.LONG.fieldOf("count").forGetter(Hoard::count)
        ).apply(instance, Hoard::new));
    }

    public static final Codec<Jackpot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Hoard.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(j -> j.entries),
            Codec.LONG.optionalFieldOf("wins", 0L).forGetter(j -> j.wins),
            Codec.STRING.optionalFieldOf("last_winner", "").forGetter(j -> j.lastWinner),
            Codec.LONG.optionalFieldOf("chips", 0L).forGetter(j -> j.chips)
    ).apply(instance, Jackpot::new));

    public static final SavedDataType<Jackpot> TYPE =
            new SavedDataType<>(FILE_ID, Jackpot::new, CODEC);

    private final List<Hoard> entries;
    private long wins;
    private String lastWinner;
    /** Chips lost at the tables, in cents. Shared out with the items, by the same share. */
    private long chips;

    public Jackpot() {
        this(List.of(), 0L, "", 0L);
    }

    private Jackpot(List<Hoard> entries, long wins, String lastWinner, long chips) {
        this.entries = new ArrayList<>(entries);
        this.wins = wins;
        this.lastWinner = lastWinner;
        this.chips = Math.max(0, chips);
    }

    /** The chips in the pot, in cents. */
    public long chips() { return chips; }

    /** Banks chips. Chips have no kinds, so unlike items they are never refused for room. */
    public void depositChips(long cents) {
        if (cents <= 0) return;
        chips = com.itemcasino.core.chips.Chips.add(chips, cents);
        setDirty();
    }

    /**
     * The one pot, wherever you ask from.
     *
     * <p>Always the overworld's storage: a per-dimension jackpot would mean a player in the Nether
     * feeding a pot that the player next to them at the same table cannot win.
     */
    public static Jackpot of(ServerLevel level) {
        MinecraftServer server = level.getServer();
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // ------------------------------------------------------------------ the hoard

    public List<Hoard> entries() { return List.copyOf(entries); }

    public long wins() { return wins; }

    public String lastWinner() { return lastWinner; }

    public boolean isEmpty() { return entries.isEmpty() && chips <= 0; }

    /** Banks a loss. Returns what was actually taken in, which is nothing once the pot is full. */
    public long deposit(ItemStack prototype, long count) {
        if (prototype.isEmpty() || count <= 0) return 0;
        for (int i = 0; i < entries.size(); i++) {
            Hoard entry = entries.get(i);
            if (ItemStack.isSameItemSameComponents(entry.prototype(), prototype)) {
                entries.set(i, new Hoard(entry.prototype(), saturatingAdd(entry.count(), count)));
                setDirty();
                return count;
            }
        }
        if (entries.size() >= MAX_ENTRIES) return 0;
        ItemStack single = prototype.copy();
        single.setCount(1);
        entries.add(new Hoard(single, count));
        setDirty();
        return count;
    }

    /** What the pot is worth, priced by the same engine that prices every wager. */
    public long totalValue(ValuationSnapshot snapshot) {
        long total = com.itemcasino.core.chips.Chips.valueOfCents(chips);
        for (Hoard entry : entries) {
            long unit = StackValuator.unitValue(entry.prototype(), snapshot);
            if (unit == Fixed.INF || unit <= 0) continue;
            total = saturatingAdd(total, saturatingMultiply(unit, entry.count()));
        }
        return total;
    }

    /** The richest few entries, for a screen that has room for a handful of lines. */
    public List<Hoard> richest(int limit, ValuationSnapshot snapshot) {
        List<Hoard> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong((Hoard h) -> {
            long unit = StackValuator.unitValue(h.prototype(), snapshot);
            return unit == Fixed.INF ? 0 : saturatingMultiply(unit, h.count());
        }).reversed());
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    // ------------------------------------------------------------------ winning it

    /**
     * What a {@code percent} share of the pot would actually hand over: whole items, rounded down per
     * kind, valued by the same engine. A quarter of a pot holding one nether star holds no nether
     * star, and the odds are priced against this number rather than against {@code share × pot}.
     */
    public long prizeValue(ValuationSnapshot snapshot, int percent) {
        return prizeValueWith(ItemStack.EMPTY, 0L, snapshot, percent);
    }

    /** The same, as if {@code offering} had already been deposited. */
    public long prizeValueWith(ItemStack offering, ValuationSnapshot snapshot, int percent) {
        return prizeValueWith(offering, 0L, snapshot, percent);
    }

    /** The same, as if {@code offering} and {@code offeredCents} chips had already been deposited. */
    public long prizeValueWith(ItemStack offering, long offeredCents, ValuationSnapshot snapshot, int percent) {
        long potChips = com.itemcasino.core.chips.Chips.add(chips, Math.max(0, offeredCents));
        long total = com.itemcasino.core.chips.Chips.valueOfCents(
                com.itemcasino.core.game.VaultOdds.share(potChips, percent));
        boolean merged = offering.isEmpty();
        for (Hoard entry : entries) {
            long count = entry.count();
            if (!merged && ItemStack.isSameItemSameComponents(entry.prototype(), offering)) {
                count = saturatingAdd(count, offering.getCount());
                merged = true;
            }
            total = saturatingAdd(total, shareValue(entry.prototype(), count, snapshot, percent));
        }
        if (!merged) {
            total = saturatingAdd(total, shareValue(offering, offering.getCount(), snapshot, percent));
        }
        return total;
    }

    private static long shareValue(ItemStack prototype, long count, ValuationSnapshot snapshot, int percent) {
        long unit = StackValuator.unitValue(prototype, snapshot);
        if (unit == Fixed.INF || unit <= 0) return 0;
        return saturatingMultiply(unit, com.itemcasino.core.game.VaultOdds.share(count, percent));
    }

    /** How many of one item the pot holds, across every variant of it. */
    public long countOf(net.minecraft.world.item.Item item) {
        long total = 0;
        for (Hoard entry : entries) {
            if (entry.prototype().is(item)) total = saturatingAdd(total, entry.count());
        }
        return total;
    }

    /** Whether {@link #deposit} would take this item in, rather than refuse a new kind. */
    public boolean canDeposit(ItemStack prototype) {
        if (prototype.isEmpty()) return false;
        if (entries.size() < MAX_ENTRIES) return true;
        for (Hoard entry : entries) {
            if (ItemStack.isSameItemSameComponents(entry.prototype(), prototype)) return true;
        }
        return false;
    }

    /**
     * Hands the entire pot to one player and empties it.
     *
     * <p>Delivered straight to their inventory, and whatever does not fit goes to their casino
     * mailbox rather than onto the ground: a pot can be tens of thousands of items, and raining that
     * many entities onto one spot is a lag spike, and a gift to whoever is standing nearby. Counts
     * are walked directly rather than split into a list of stacks first, so no size of pot is ever
     * truncated.
     *
     * @return what the pot was worth, for the record
     */
    public long award(ServerPlayer player) {
        return award(player, 100);
    }

    /**
     * Hands a {@code percent} share of the pot to one player: of every kind, that share of its count
     * rounded down. The rest stays in the pot for the next winner.
     *
     * @return what the share was worth, for the record; zero when it rounds down to nothing
     */
    public long award(ServerPlayer player, int percent) {
        Award award = awardShare(player, percent);
        if (award.chipCents() > 0) {
            // No table to credit: the chips arrive on a card of their own.
            com.itemcasino.player.CasinoMailbox.send(player.level().getServer(), player.getUUID(),
                    com.itemcasino.chips.ChipCards.newCard(award.chipCents()));
        }
        return award.value();
    }

    /** What a share handed over: its value, and the chips the caller must put on a card. */
    public record Award(long value, long chipCents) {
        public static final Award NOTHING = new Award(0L, 0L);
    }

    /** A share taken out of the pot and not yet handed over: the stacks, the chips, and their worth. */
    public record Prize(String winner, int share, long value, long chipCents, List<ItemStack> stacks) {
        public boolean isEmpty() { return stacks.isEmpty() && chipCents <= 0; }
    }

    /**
     * Hands a {@code percent} share of the pot to one player: of every kind of item, that share of
     * its count rounded down, delivered to the player; and that share of the chips, returned to the
     * caller to credit to whichever card is on the table. The rest stays in the pot.
     */
    public Award awardShare(ServerPlayer player, int percent) {
        Prize prize = takeShare(player.getName().getString(), percent);
        if (prize.isEmpty()) return Award.NOTHING;
        deliver(player.level().getServer(), player.getUUID(), prize);
        return new Award(prize.value(), prize.chipCents());
    }

    /**
     * Takes a {@code percent} share out of the pot, of every kind that share of its count rounded
     * down and that share of the chips, and hands it to the caller to deliver. Taken at once so
     * nobody else can win the same items; delivered when the caller says, so a table can keep the
     * news until its animation has finished.
     */
    public Prize takeShare(String winner, int percent) {
        int share = com.itemcasino.core.game.VaultOdds.clampShare(percent);
        if (isEmpty()) return new Prize(winner, share, 0L, 0L, List.of());
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        List<Hoard> won = new ArrayList<>(entries.size());
        List<Hoard> kept = new ArrayList<>(entries.size());
        long chipCents = com.itemcasino.core.game.VaultOdds.share(chips, share);
        long value = com.itemcasino.core.chips.Chips.valueOfCents(chipCents);
        for (Hoard entry : entries) {
            long taken = com.itemcasino.core.game.VaultOdds.share(entry.count(), share);
            if (taken > 0) {
                won.add(new Hoard(entry.prototype(), taken));
                value = saturatingAdd(value, shareValue(entry.prototype(), taken, snapshot, 100));
            }
            if (entry.count() - taken > 0) kept.add(new Hoard(entry.prototype(), entry.count() - taken));
        }
        if (won.isEmpty() && chipCents <= 0) return new Prize(winner, share, 0L, 0L, List.of());
        entries.clear();
        entries.addAll(kept);
        chips -= chipCents;
        wins++;
        lastWinner = winner;
        setDirty();

        List<ItemStack> stacks = new ArrayList<>(won.size());
        for (Hoard entry : won) {
            // Legal stacks, so the prize can be saved with a table that is still showing its draw.
            int max = Math.max(1, entry.prototype().getMaxStackSize());
            long left = entry.count();
            while (left > 0) {
                int chunk = (int) Math.min(max, left);
                ItemStack stack = entry.prototype().copy();
                stack.setCount(chunk);
                stacks.add(stack);
                left -= chunk;
            }
        }
        ItemCasino.LOGGER.info("[jackpot] {} took {}% of the pot: {} stacks across {} kinds and {} chip cents",
                winner, share, stacks.size(), won.size(), chipCents);
        return new Prize(winner, share, value, chipCents, List.copyOf(stacks));
    }

    /**
     * Hands over a prize's items (inventory first, then the casino mailbox), records it and tells the
     * server. The chips are the caller's to credit: onto the card on the table, or a card of their own.
     */
    public static void deliver(MinecraftServer server, java.util.UUID player, Prize prize) {
        if (server == null || prize.isEmpty()) return;
        if (!prize.stacks().isEmpty()) {
            com.itemcasino.player.CasinoMailbox.send(server, player, new ArrayList<>(prize.stacks()));
        }
        com.itemcasino.player.CasinoStats.recordJackpot(server, player, prize.value());
        announce(server, prize.winner(), prize.value(), prize.share());
    }

    /** Announced in chips, the one unit that adds up items and chips alike. */
    private static void announce(MinecraftServer server, String winner, long value, int share) {
        Component who = Component.literal(winner).withStyle(ChatFormatting.GOLD);
        String worth = com.itemcasino.core.chips.Chips.format(com.itemcasino.core.chips.Chips.centsForValue(value));
        Component message = share >= 100
                ? Component.translatable("itemcasino.jackpot.announce", who, worth)
                : Component.translatable("itemcasino.jackpot.announce_share", who, share, worth);
        server.getPlayerList().broadcastSystemMessage(message.copy().withStyle(ChatFormatting.YELLOW), false);
    }

    /**
     * The chance every wager carries, whether or not the player is thinking about the pot.
     *
     * <p>Small enough that it is never a reason to play, and only offered on a wager big enough to
     * be a real risk — otherwise the cheapest possible bet, repeated, becomes a free lottery ticket
     * dispenser, which is how a jackpot gets drained by a macro rather than won.
     *
     * @return true when the pot was just taken
     */
    public boolean rollAmbient(ServerPlayer player, long wagerValue) {
        if (isEmpty()) return false;
        if (wagerValue < CasinoConfig.SERVER.jackpotMinWagerValue.get()) return false;
        int ppm = CasinoConfig.SERVER.jackpotAmbientPpm.get();
        if (ppm <= 0) return false;
        if (player.level().getRandom().nextInt(1_000_000) >= ppm) return false;
        award(player);
        return true;
    }

    // ------------------------------------------------------------------ arithmetic

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    private static long saturatingMultiply(long a, long b) {
        if (a == 0 || b == 0) return 0;
        long product = a * b;
        if (a != product / b || (a == Long.MIN_VALUE && b == -1)) return Long.MAX_VALUE;
        return product;
    }

    /** Convenience for the many call sites that only have a level and a loss to bank. */
    public static void bankChips(ServerLevel level, long cents) {
        if (cents <= 0) return;
        if (!CasinoConfig.SERVER.jackpotEnabled.get()) return;
        of(level).depositChips(cents);
    }

    public static void bank(ServerLevel level, ItemStack prototype, long count) {
        if (count <= 0 || prototype.isEmpty()) return;
        if (!CasinoConfig.SERVER.jackpotEnabled.get()) return;
        of(level).deposit(prototype, count);
    }

    /** The pot's worth right now, for callers that have no snapshot to hand. */
    public static long valueOf(ServerLevel level) {
        return of(level).totalValue(ValuationEngine.snapshot());
    }
}
