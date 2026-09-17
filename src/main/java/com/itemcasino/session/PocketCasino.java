package com.itemcasino.session;

import com.itemcasino.ItemCasino;
import com.itemcasino.menu.BlackjackMenu;
import com.itemcasino.menu.DiceMenu;
import com.itemcasino.menu.UpgraderMenu;
import com.itemcasino.network.CasinoNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The pocket games: private, single-view, and deliberately not persisted.
 *
 * <p>A table in the world has to survive chunk unloads and server restarts, which is why its
 * session is written to disk and repaired on load. A pocket session never needs any of that,
 * because it cannot outlive the screen: closing the menu, logging out or stopping the server all
 * settle whatever was decided and hand everything back. Nothing is left holding an item, so there
 * is nothing to persist and nothing to repair — the whole class of crash-recovery bugs simply does
 * not apply here.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class PocketCasino {

    private static final Map<UUID, Host> ACTIVE = new ConcurrentHashMap<>();

    private PocketCasino() {}

    /** Opens a private game for this player, replacing any session they had running. */
    public static void open(ServerPlayer player, GameKind kind) {
        close(player);
        Host host = new Host(player.getUUID(), kind);
        ACTIVE.put(player.getUUID(), host);
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> menuFor(kind, containerId, inventory, host, viewer),
                Component.translatable(kind.titleKey())),
                buffer -> buffer.writeBoolean(true));
    }

    @Nullable
    private static AbstractContainerMenu menuFor(GameKind kind, int containerId,
                                                 net.minecraft.world.entity.player.Inventory inventory,
                                                 Host host, Player viewer) {
        return switch (kind) {
            case UPGRADER -> new UpgraderMenu(containerId, inventory, host.session, viewer);
            case DICE -> new DiceMenu(containerId, inventory, host.session, viewer);
            case BLACKJACK -> new BlackjackMenu(containerId, inventory, host.session, viewer);
        };
    }

    /** Settles anything decided, returns everything, and forgets the session. */
    public static void close(Player player) {
        Host host = ACTIVE.remove(player.getUUID());
        if (host == null) return;
        host.teardown(player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
    }

    /**
     * Closes one specific session, and only if it is still the live one.
     *
     * <p>This identity check is the whole point. Opening a pocket game while another is on screen
     * makes the server close the old menu <em>after</em> the new session is already registered, so
     * the dying menu's teardown arrives late and, keyed only by player, would liquidate the game
     * that just started. Matching on the host itself makes that late call a no-op.
     */
    private static void closeIfCurrent(Host host, Player player) {
        if (!ACTIVE.remove(host.ownerId, host)) return;
        host.teardown(player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
    }

    @Nullable
    public static CasinoSession sessionOf(Player player) {
        Host host = ACTIVE.get(player.getUUID());
        return host == null ? null : host.session;
    }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Host host : ACTIVE.values()) {
            if (host.levelOrNull() == null) continue;
            try {
                host.session.tick();
            } catch (RuntimeException e) {
                // One broken pocket game must not take the server tick down with it. Drop the
                // session the same way a logout would: settled, refunded, forgotten.
                ItemCasino.LOGGER.error("Pocket casino session failed to tick; closing it", e);
                closeIfCurrent(host, host.player());
            }
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        close(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Players are still loaded here, so their winnings go into their inventory rather than
        // onto a floor nobody will ever walk back to.
        for (UUID id : Set.copyOf(ACTIVE.keySet())) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);
            Host host = ACTIVE.remove(id);
            if (host != null) host.teardown(player);
        }
    }

    // ------------------------------------------------------------------ the host

    private static final class Host implements SessionHost {

        private final UUID ownerId;
        private final CasinoSession session;

        private Host(UUID ownerId, GameKind kind) {
            this.ownerId = ownerId;
            this.session = kind.newSession(this);
        }

        @Nullable
        private ServerPlayer player() {
            net.minecraft.server.MinecraftServer server =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.getPlayerList().getPlayer(ownerId);
        }

        @Nullable
        ServerLevel levelOrNull() {
            ServerPlayer player = player();
            return player != null && player.level() instanceof ServerLevel l ? l : null;
        }

        @Override
        public ServerLevel hostLevel() {
            ServerLevel level = levelOrNull();
            if (level == null) throw new IllegalStateException("pocket session without a level");
            return level;
        }

        @Override
        public void markDirty() {
            // Nothing to persist: the session dies with the screen.
        }

        @Override
        public void broadcast(IntFunction<CustomPacketPayload> factory) {
            ServerPlayer player = player();
            if (player == null) return;
            CasinoNetwork.send(player, factory.apply(player.containerMenu.containerId));
        }

        @Nullable
        @Override
        public ServerPlayer seatedPlayer() {
            return player();
        }

        @Nullable
        @Override
        public UUID seatId(int index) {
            return index == 0 ? ownerId : null;
        }

        @Override
        public boolean isSeated(@Nullable Player candidate) {
            return candidate != null && candidate.getUUID().equals(ownerId);
        }

        @Override
        public int seatIndex(@Nullable Player candidate) {
            return isSeated(candidate) ? 0 : -1;
        }

        @Override
        public boolean stillValid(Player candidate) {
            return isSeated(candidate) && ACTIVE.get(ownerId) == this;
        }

        /**
         * Whatever will not fit goes to the owner's casino mailbox. {@code player.drop} was used
         * before, which on logout threw winnings onto a floor nobody would come back to — and
         * fires the toss event, so eight gold ingots of overflow could summon a goblin.
         */
        @Override
        public void dropOverflow(ItemStack stack) {
            net.minecraft.server.MinecraftServer server =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null && com.itemcasino.player.CasinoMailbox.send(server, ownerId, stack.copy())) {
                return;
            }
            ServerPlayer player = player();
            if (player != null) player.spawnAtLocation(player.level(), stack);
        }

        @Override
        public void onViewerClosed(Player player) {
            closeIfCurrent(this, player);
        }

        private void teardown(@Nullable ServerPlayer player) {
            try {
                if (session.gameState().holdsEscrow()) session.forceSettle();
                session.liquidate(player);
            } catch (RuntimeException e) {
                ItemCasino.LOGGER.error("Failed to tear down a pocket casino session", e);
            }
        }
    }
}
