package com.itemcasino.valuation;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.core.value.EstimatedValues;
import com.itemcasino.core.value.ValueSolution;
import com.itemcasino.network.CasinoNetwork;
import com.itemcasino.valuation.harvest.RecipeHarvester;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the current {@link ValuationSnapshot} and rebuilds it when the datapacks change.
 *
 * <h2>Threading</h2>
 * The harvest touches {@code RecipeManager} and the item registry, so it runs on the server thread
 * (it is the cheap half: about 10 ms on vanilla). The relaxation is pure arithmetic over primitive
 * arrays with no Minecraft state in it at all, so it runs on {@link Util#backgroundExecutor()} and
 * the finished snapshot is published back on the server thread. Nothing ever blocks a tick waiting
 * for it: until the first snapshot lands, every table reports "calibrating" and refuses wagers.
 *
 * <h2>Why not a reload listener</h2>
 * Recipes are not yet applied while reload listeners are preparing, so a listener would have to be
 * ordered after {@code VanillaServerListeners.RECIPES} and still only see them in its apply phase.
 * {@link OnDatapackSyncEvent} fires once everything is loaded and bound, which is exactly the
 * moment we want, and it is also where the advisory client table has to be sent anyway.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class ValuationEngine {

    private static final AtomicReference<ValuationSnapshot> CURRENT =
            new AtomicReference<>(ValuationSnapshot.EMPTY);
    private static final AtomicBoolean BUILDING = new AtomicBoolean(false);

    private ValuationEngine() {}

    /** Lock-free read, safe from the server thread at any time. */
    public static ValuationSnapshot snapshot() {
        return CURRENT.get();
    }

    public static boolean isReady() {
        return CURRENT.get().isReady();
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        rebuild(event.getServer(), null);
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) {
            // A global sync means /reload or world load: the recipe set may have changed.
            rebuild(event.getPlayerList().getServer(), null);
        } else {
            // A single player joined: they only need the advisory table for the target picker.
            CasinoNetwork.sendValueTable(player, CURRENT.get());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CURRENT.set(ValuationSnapshot.EMPTY);
    }

    // ------------------------------------------------------------------ build

    /** Forces an asynchronous rebuild. Safe to call repeatedly; overlapping calls are dropped. */
    public static void rebuild(MinecraftServer server, Runnable whenDone) {
        if (server == null) return;
        if (!BUILDING.compareAndSet(false, true)) {
            ItemCasino.LOGGER.debug("Valuation rebuild already in flight, ignoring request");
            return;
        }

        ServerLevel overworld = server.overworld();
        final long t0 = System.nanoTime();

        RecipeHarvester.Harvest harvest;
        try {
            harvest = RecipeHarvester.harvest(overworld);   // server thread, touches RecipeManager
        } catch (RuntimeException e) {
            BUILDING.set(false);
            ItemCasino.LOGGER.error("Recipe harvest failed; item values are unavailable", e);
            return;
        }
        final long harvestMs = (System.nanoTime() - t0) / 1_000_000;

        CompletableFuture
                .supplyAsync(() -> solve(harvest), Util.backgroundExecutor())
                .whenCompleteAsync((snapshot, error) -> {
                    BUILDING.set(false);
                    if (error != null || snapshot == null) {
                        ItemCasino.LOGGER.error("Valuation solve failed", error);
                        return;
                    }
                    CURRENT.set(snapshot);
                    report(snapshot, harvest, harvestMs);
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        CasinoNetwork.sendValueTable(player, snapshot);
                    }
                    if (whenDone != null) whenDone.run();
                }, server);
    }

    private static ValuationSnapshot solve(RecipeHarvester.Harvest harvest) {
        long t0 = System.nanoTime();
        // Known values first, then estimates for whatever the base values cannot reach. The split
        // is what makes a guess safe: estimated items can be wagered at their (low) estimate but
        // are never offered as targets. See EstimatedValues.
        EstimatedValues.Result result = EstimatedValues.solve(harvest.graph(), harvest.seed(),
                harvest.pinned(), harvest.fallback());

        ItemCasino.LOGGER.debug("Relaxation: {} improvements, {} estimated, {} ms",
                result.solution().relaxations(), result.estimatedCount(),
                (System.nanoTime() - t0) / 1_000_000);

        return new ValuationSnapshot(harvest.graph(), result.solution(), harvest.index(),
                harvest.items(), result.values(), harvest.targetable(), result.estimated(),
                harvest.tableHash());
    }

    private static void report(ValuationSnapshot snapshot, RecipeHarvester.Harvest harvest,
                               long harvestMs) {
        ItemCasino.LOGGER.info(
                "Item values ready: {} items, {} priced ({} of them estimated), {} unpriced, {} recipes ({} skipped), harvest {} ms",
                snapshot.itemCount(), snapshot.itemCount() - snapshot.unpricedCount(),
                snapshot.estimatedCount(), snapshot.unpricedCount(), harvest.recipesSeen(),
                harvest.recipesSkipped(), harvestMs);

        ValueSolution solution = snapshot.solution();
        if (solution == null) return;
        if (solution.capped()) {
            ItemCasino.LOGGER.warn("The value solver hit its iteration guard. Some values are "
                    + "unreliable; run /casino diagnose and look for a value-creating recipe loop.");
        }
        if (CasinoConfig.COMMON.reportDerivationCycles.get() && !solution.cycles().isEmpty()) {
            List<int[]> cycles = solution.cycles();
            ItemCasino.LOGGER.warn("{} derivation cycle(s) in the recipe graph; the items in them "
                    + "price each other and may have collapsed to the floor:", cycles.size());
            int shown = 0;
            for (int[] cycle : cycles) {
                if (shown++ >= 5) { ItemCasino.LOGGER.warn("  ... and {} more", cycles.size() - 5); break; }
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 0; i < cycle.length; i++) {
                    if (i > 0) sb.append(" -> ");
                    sb.append(snapshot.itemAt(cycle[i]));
                }
                ItemCasino.LOGGER.warn(sb.toString());
            }
        }
    }
}
