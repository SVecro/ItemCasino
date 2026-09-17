package com.itemcasino.player;

import com.itemcasino.ItemCasino;
import com.itemcasino.jackpot.Jackpot;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Items the casino owes a player who is not there to take them.
 *
 * <h2>Why it exists</h2>
 * Every other answer to "the owner is gone" was wrong in a way a player would find. Dropping the
 * items at the table hands them to whoever walks past. Keeping them in the table keeps the seat
 * locked for everyone else. Putting them in the inventory of a player who has just disconnected
 * writes them into a save that has already been taken, so they vanish. The mailbox is the one place
 * that is owned by the right person and cannot be reached by anybody else.
 *
 * <h2>What goes in</h2>
 * A stake left in a slot when its owner disconnected; a win abandoned at a table; a jackpot too big
 * for one inventory; whatever a destroyed table was holding for someone who is offline. It is
 * delivered on login, on respawn, when the player opens any casino table, and from the stats screen.
 *
 * <p>Stored as the jackpot stores its hoard — one prototype and a {@code long} count per distinct
 * item — so a large jackpot does not become thousands of entries.
 */
public class CasinoMailbox extends SavedData {

    private static final String FILE_ID = "itemcasino_mailbox";

    private record Box(UUID owner, List<Jackpot.Hoard> items) {
        static final Codec<Box> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Box::owner),
                Jackpot.Hoard.CODEC.listOf().fieldOf("items").forGetter(Box::items)
        ).apply(i, Box::new));
    }

    public static final Codec<CasinoMailbox> CODEC = Box.CODEC.listOf()
            .xmap(CasinoMailbox::new, CasinoMailbox::boxes);

    public static final SavedDataType<CasinoMailbox> TYPE =
            new SavedDataType<>(FILE_ID, CasinoMailbox::new, CODEC);

    private final Map<UUID, List<Jackpot.Hoard>> boxes = new HashMap<>();

    public CasinoMailbox() {}

    private CasinoMailbox(List<Box> stored) {
        for (Box box : stored) {
            if (!box.items().isEmpty()) boxes.put(box.owner(), new ArrayList<>(box.items()));
        }
    }

    private List<Box> boxes() {
        List<Box> out = new ArrayList<>(boxes.size());
        boxes.forEach((owner, items) -> { if (!items.isEmpty()) out.add(new Box(owner, List.copyOf(items))); });
        return out;
    }

    public static CasinoMailbox of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // ------------------------------------------------------------------ in

    /** Keeps {@code count} of this item for its owner. Never refuses: refusing would destroy it. */
    public void keep(UUID owner, ItemStack prototype, long count) {
        if (prototype.isEmpty() || count <= 0) return;
        List<Jackpot.Hoard> items = boxes.computeIfAbsent(owner, id -> new ArrayList<>());
        for (int i = 0; i < items.size(); i++) {
            Jackpot.Hoard entry = items.get(i);
            if (ItemStack.isSameItemSameComponents(entry.prototype(), prototype)) {
                long sum = entry.count() + count;
                items.set(i, new Jackpot.Hoard(entry.prototype(), sum < 0 ? Long.MAX_VALUE : sum));
                setDirty();
                return;
            }
        }
        ItemStack single = prototype.copy();
        single.setCount(1);
        items.add(new Jackpot.Hoard(single, count));
        setDirty();
    }

    public long pendingCount(UUID owner) {
        long total = 0;
        for (Jackpot.Hoard entry : boxes.getOrDefault(owner, List.of())) total += entry.count();
        return total;
    }

    public long pendingCount(UUID owner, net.minecraft.world.item.Item item) {
        long total = 0;
        for (Jackpot.Hoard entry : boxes.getOrDefault(owner, List.of())) {
            if (entry.prototype().is(item)) total += entry.count();
        }
        return total;
    }

    // ------------------------------------------------------------------ out

    /**
     * Moves as much of this player's mailbox into their inventory as fits.
     *
     * @return how many items were handed over
     */
    public long deliver(ServerPlayer player) {
        List<Jackpot.Hoard> items = boxes.get(player.getUUID());
        if (items == null || items.isEmpty() || !canReceive(player)) return 0;
        long delivered = 0;
        List<Jackpot.Hoard> left = new ArrayList<>();
        for (Jackpot.Hoard entry : items) {
            long remaining = giveUpTo(player, entry.prototype(), entry.count());
            delivered += entry.count() - remaining;
            if (remaining > 0) left.add(new Jackpot.Hoard(entry.prototype(), remaining));
        }
        if (left.isEmpty()) boxes.remove(player.getUUID());
        else boxes.put(player.getUUID(), left);
        if (delivered > 0) setDirty();
        return delivered;
    }

    /** Delivers and tells the player what happened. Cheap when the box is empty. */
    public static void deliverAndNotify(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        CasinoMailbox mailbox = of(server);
        if (mailbox.pendingCount(player.getUUID()) <= 0) return;
        long delivered = mailbox.deliver(player);
        long left = mailbox.pendingCount(player.getUUID());
        if (delivered > 0) {
            player.displayClientMessage(Component.translatable("itemcasino.mailbox.delivered", delivered)
                    .withStyle(ChatFormatting.GOLD), false);
        }
        if (left > 0) {
            player.displayClientMessage(Component.translatable("itemcasino.mailbox.waiting", left)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }

    // ------------------------------------------------------------------ the one entry point callers use

    /**
     * Hands items to their owner: into the inventory when they are here to take them, into the
     * mailbox when they are not, and into the mailbox for whatever does not fit.
     *
     * @return false when there is no owner at all, so the caller must decide where the items go
     */
    public static boolean send(MinecraftServer server, @Nullable UUID owner, List<ItemStack> stacks) {
        if (owner == null) return false;
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        boolean present = player != null && canReceive(player);
        CasinoMailbox mailbox = null;
        long kept = 0;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            long remaining = present ? giveUpTo(player, stack, stack.getCount()) : stack.getCount();
            if (remaining > 0) {
                if (mailbox == null) mailbox = of(server);
                mailbox.keep(owner, stack, remaining);
                kept += remaining;
            }
        }
        if (kept > 0) {
            ItemCasino.LOGGER.info("[mailbox] kept {} items for {}", kept, owner);
            if (present) {
                player.displayClientMessage(Component.translatable("itemcasino.mailbox.waiting", kept)
                        .withStyle(ChatFormatting.YELLOW), false);
            }
        }
        return true;
    }

    public static boolean send(MinecraftServer server, @Nullable UUID owner, ItemStack stack) {
        return send(server, owner, List.of(stack));
    }

    /**
     * A disconnected player's inventory has already been written to disk, and a dead one's is about
     * to be discarded: anything added to either is lost.
     */
    private static boolean canReceive(ServerPlayer player) {
        return player.isAlive() && !player.hasDisconnected() && !player.isRemoved();
    }

    /** Adds up to {@code count} copies in legal stacks; returns what did not fit. */
    private static long giveUpTo(ServerPlayer player, ItemStack prototype, long count) {
        int max = Math.max(1, prototype.getMaxStackSize());
        long remaining = count;
        while (remaining > 0) {
            int take = (int) Math.min(max, remaining);
            ItemStack copy = prototype.copy();
            copy.setCount(take);
            player.getInventory().add(copy);          // mutates copy down to what did not fit
            remaining -= take - copy.getCount();
            if (!copy.isEmpty()) break;               // full: stop rather than spin
        }
        return remaining;
    }
}
