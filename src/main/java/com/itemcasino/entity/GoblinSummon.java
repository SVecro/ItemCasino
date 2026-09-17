package com.itemcasino.entity;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.registry.CasinoEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;

/**
 * Throw gold on the ground and see who turns up.
 *
 * <p>A deliberate ritual rather than a random encounter: it takes a specific offering, it works
 * every time, and it is discoverable by anyone who has ever dropped a stack of gold by accident.
 * The gold is gone — that is his fee for showing up, and a leprechaun who worked for free would be
 * a strange leprechaun.
 */
@EventBusSubscriber(modid = ItemCasino.MOD_ID)
public final class GoblinSummon {

    private GoblinSummon() {}

    /**
     * Only a deliberate toss counts: Q, or dragging the stack out of the inventory screen.
     *
     * <p>This used to listen for any item entity joining the world, which also fires when a mined
     * gold block drops, when a chest full of ingots is broken, when a player dies carrying gold, and
     * when a chunk with a gold block lying in it loads — and every one of those summoned him and ate
     * the gold. {@code ItemTossEvent} fires for the throw and nothing else.
     */
    @SubscribeEvent
    public static void onItemTossed(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!player.isAlive() || player.hasDisconnected()) return;
        if (!CasinoConfig.SERVER.goblinEnabled.get()) return;
        ItemEntity item = event.getEntity();
        if (!isOffering(item.getItem())) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        // One goblin at a time is plenty.
        if (!level.getEntitiesOfClass(GamblerGoblin.class,
                player.getBoundingBox().inflate(24.0D)).isEmpty()) {
            return;
        }

        GamblerGoblin goblin = CasinoEntities.GOBLIN.get().create(level,
                net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (goblin == null) return;
        // snapTo, not moveTo: the whole family was renamed in 1.21 and the old name no longer
        // exists on Entity at all.
        goblin.snapTo(item.getX(), item.getY(), item.getZ(),
                level.getRandom().nextFloat() * 360F, 0F);
        goblin.setCustomName(Component.translatable("entity.itemcasino.gambler_goblin"));

        // Cancelled: the tossed stack never enters the world. That is his fee.
        event.setCanceled(true);
        level.addFreshEntity(goblin);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, item.getX(), item.getY() + 0.5,
                item.getZ(), 30, 0.5, 0.5, 0.5, 0.05);
        level.playSound(null, item.blockPosition(), SoundEvents.ILLUSIONER_PREPARE_MIRROR,
                SoundSource.NEUTRAL, 0.9F, 1.5F);
        player.displayClientMessage(Component.translatable("itemcasino.goblin.arrives"), false);
    }

    /** A gold block, or a handful of ingots. Enough that nobody summons him by fumbling one nugget. */
    private static boolean isOffering(ItemStack stack) {
        if (stack.is(Items.GOLD_BLOCK)) return true;
        return stack.is(Items.GOLD_INGOT)
                && stack.getCount() >= CasinoConfig.SERVER.goblinIngotCost.get();
    }
}
