package com.itemcasino.command;

import com.itemcasino.ItemCasino;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.core.value.Odds;
import com.itemcasino.core.value.ValueGraph;
import com.itemcasino.core.value.ValueSolution;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Operator tools. The value table is the mod's most argued-about artefact, so being able to answer
 * "why is that worth that" with one command is worth the hundred lines.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class CasinoCommands {

    private CasinoCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("casino")
                .then(Commands.literal("value")
                        .executes(ctx -> valueOfHeld(ctx.getSource()))
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(ctx -> valueOf(ctx.getSource(),
                                        ItemArgument.getItem(ctx, "item")))))
                .then(Commands.literal("odds")
                        .then(Commands.argument("target", ItemArgument.item(event.getBuildContext()))
                                .executes(ctx -> odds(ctx.getSource(),
                                        ItemArgument.getItem(ctx, "target")))))
                .then(Commands.literal("diagnose")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> diagnose(ctx.getSource())))
                .then(Commands.literal("dump")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> dump(ctx.getSource())))
                .then(Commands.literal("rebuild")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> rebuild(ctx.getSource())));

        event.getDispatcher().register(root);
    }

    // ------------------------------------------------------------------ subcommands

    private static int valueOfHeld(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Hold an item, or name one explicitly."));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("You are not holding anything."));
            return 0;
        }
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!snapshot.isReady()) return notReady(source);

        long unit = StackValuator.unitValue(held, snapshot);
        long total = StackValuator.value(held, snapshot);
        source.sendSuccess(() -> Component.literal(
                held.getHoverName().getString() + " x" + held.getCount()
                        + "  unit " + Fixed.format(unit) + "  total " + Fixed.format(total)
                        + (snapshot.isEstimated(held.getItem()) ? "  (estimated)" : "")), false);
        explainDerivation(source, snapshot, held.getItem());

        StackValuator.Rejection rejection = StackValuator.reject(held, snapshot);
        if (rejection != null) {
            source.sendSuccess(() -> Component.translatable(rejection.translationKey()), false);
        }
        return 1;
    }

    private static int valueOf(CommandSourceStack source, ItemInput input) {
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!snapshot.isReady()) return notReady(source);

        Item item = input.getItem();
        long value = snapshot.value(item);
        source.sendSuccess(() -> Component.literal(
                key(item) + " = " + Fixed.format(value)
                        + (snapshot.isEstimated(item) ? "  (estimated: wager only)" : "")
                        + (snapshot.isTargetable(item) || snapshot.isEstimated(item)
                                ? "" : "  (not selectable as a target)")), false);
        explainDerivation(source, snapshot, item);
        return 1;
    }

    private static int odds(CommandSourceStack source, ItemInput target) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return notReady(source);
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!snapshot.isReady()) return notReady(source);

        ItemStack held = player.getMainHandItem();
        long inputValue = held.isEmpty() ? Fixed.INF : StackValuator.value(held, snapshot);
        long targetValue = snapshot.value(target.getItem());
        int ppm = Odds.ppm(inputValue, targetValue);

        source.sendSuccess(() -> Component.literal(
                Fixed.format(inputValue) + " -> " + Fixed.format(targetValue) + "  =  "
                        + (ppm == Odds.ILLEGAL ? "not a legal wager" : Odds.percent(ppm) + " %")), false);
        return 1;
    }

    private static int diagnose(CommandSourceStack source) {
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!snapshot.isReady()) return notReady(source);
        ValueSolution solution = snapshot.solution();

        source.sendSuccess(() -> Component.literal(
                "items " + snapshot.itemCount()
                        + " | estimated " + snapshot.estimatedCount()
                        + " | unpriced " + snapshot.unpricedCount()
                        + " | relaxations " + (solution == null ? 0 : solution.relaxations())
                        + " | solver capped " + (solution != null && solution.capped())), false);

        if (solution != null && !solution.cycles().isEmpty()) {
            List<int[]> cycles = solution.cycles();
            source.sendSuccess(() -> Component.literal(
                    cycles.size() + " derivation cycle(s):"), false);
            int shown = 0;
            for (int[] cycle : cycles) {
                if (shown++ >= 8) break;
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 0; i < cycle.length; i++) {
                    if (i > 0) sb.append(" -> ");
                    sb.append(key(snapshot.itemAt(cycle[i])));
                }
                String line = sb.toString();
                source.sendSuccess(() -> Component.literal(line), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal("no derivation cycles"), false);
        }
        return 1;
    }

    private static int dump(CommandSourceStack source) {
        ValuationSnapshot snapshot = ValuationEngine.snapshot();
        if (!snapshot.isReady()) return notReady(source);

        // Written to the world folder, never to chat: a large pack produces megabytes of CSV.
        Path out = source.getServer().getServerDirectory().resolve("itemcasino-values.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("item,value_micro,value,targetable,estimated,source\n");
            ValueSolution solution = snapshot.solution();
            ValueGraph graph = snapshot.graph();
            for (int i = 0; i < snapshot.itemCount(); i++) {
                long value = snapshot.valueAt(i);
                String origin = "none";
                if (solution != null && graph != null) {
                    int via = solution.via()[i];
                    if (via >= 0) origin = String.valueOf(graph.conversionSource(via));
                    else if (value != Fixed.INF) origin = snapshot.estimatedAt(i) ? "estimate" : "base-value";
                }
                writer.write(key(snapshot.itemAt(i)) + ","
                        + (value == Fixed.INF ? "" : Long.toString(value)) + ","
                        + Fixed.format(value) + ","
                        + snapshot.targetableAt(i) + ","
                        + snapshot.estimatedAt(i) + ","
                        + origin + "\n");
            }
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not write the dump: " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Wrote " + out.getFileName()
                + " (" + snapshot.itemCount() + " items) to the server directory."), true);
        return 1;
    }

    private static int rebuild(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Rebuilding item values..."), true);
        ValuationEngine.rebuild(source.getServer(), () ->
                source.sendSuccess(() -> Component.literal("Item values rebuilt."), true));
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    /** Prints the winning conversion for an item, which is the answer to "why is it worth that". */
    private static void explainDerivation(CommandSourceStack source, ValuationSnapshot snapshot,
                                          Item item) {
        ValueSolution solution = snapshot.solution();
        ValueGraph graph = snapshot.graph();
        if (solution == null || graph == null) return;

        for (int i = 0; i < snapshot.itemCount(); i++) {
            if (snapshot.itemAt(i) != item) continue;
            int via = solution.via()[i];
            if (via < 0) {
                source.sendSuccess(() -> Component.literal(
                        snapshot.isEstimated(item)
                                ? "  derived from: an estimate by rarity (no base value reaches it)"
                                : "  derived from: a base value or a config override"), false);
            } else {
                Object recipe = graph.conversionSource(via);
                int count = graph.outputCountOf(via);
                source.sendSuccess(() -> Component.literal(
                        "  derived from: " + recipe + " (x" + count + " per craft)"), false);
            }
            return;
        }
    }

    private static int notReady(CommandSourceStack source) {
        source.sendFailure(Component.translatable("itemcasino.reject.not_ready"));
        return 0;
    }

    private static String key(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "?" : id.toString();
    }
}
