package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.itemcasino.entity.GamblerGoblin;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoEntities {

    public static final DeferredRegister<EntityType<?>> REGISTER =
            DeferredRegister.create(Registries.ENTITY_TYPE, ItemCasino.MOD_ID);

    /**
     * MISC rather than CREATURE: he is never part of natural spawning, only summoned, and a
     * category the spawn algorithm ignores keeps him out of every mob cap on the server.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<GamblerGoblin>> GOBLIN =
            REGISTER.register("gambler_goblin", () -> EntityType.Builder
                    .of(GamblerGoblin::new, MobCategory.MISC)
                    .sized(0.6F, 1.2F)
                    .eyeHeight(1.0F)
                    .clientTrackingRange(10)
                    .build(ResourceKeyHelper.key("gambler_goblin")));

    private CasinoEntities() {}

    /** {@code EntityType.Builder#build} wants the registry key the type is about to be stored at. */
    private static final class ResourceKeyHelper {
        static net.minecraft.resources.ResourceKey<EntityType<?>> key(String name) {
            return net.minecraft.resources.ResourceKey.create(Registries.ENTITY_TYPE,
                    ItemCasino.id(name));
        }
    }
}
