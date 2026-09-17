package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoCreativeTab {

    public static final DeferredRegister<CreativeModeTab> REGISTER =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ItemCasino.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = REGISTER.register(
            "main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemgroup.itemcasino.main"))
                    .icon(() -> CasinoItems.UPGRADER.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(CasinoItems.GAME_CORE.get());
                        output.accept(CasinoItems.UPGRADER.get());

                        output.accept(CasinoItems.DICE.get());
                        output.accept(CasinoItems.BLACKJACK_TABLE.get());
                        output.accept(CasinoItems.COIN_FLIP.get());
                        output.accept(CasinoItems.SLOT_MACHINE.get());
                        output.accept(CasinoItems.VAULT.get());
                        output.accept(CasinoItems.MINE_FIELD.get());
                        output.accept(CasinoItems.CASHIER.get());
                        output.accept(CasinoItems.CHIP_CARD.get());
                        output.accept(CasinoItems.POCKET_UPGRADER.get());
                        output.accept(CasinoItems.POCKET_DICE.get());
                        output.accept(CasinoItems.POCKET_BLACKJACK.get());
                    })
                    .build());

    private CasinoCreativeTab() {}
}
