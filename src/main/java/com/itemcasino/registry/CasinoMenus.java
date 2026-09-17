package com.itemcasino.registry;

import com.itemcasino.ItemCasino;
import com.itemcasino.menu.BlackjackMenu;
import com.itemcasino.menu.CoinFlipMenu;
import com.itemcasino.menu.CashierMenu;
import com.itemcasino.menu.MineFieldMenu;
import com.itemcasino.menu.SlotMachineMenu;
import com.itemcasino.menu.VaultMenu;
import com.itemcasino.menu.DiceMenu;
import com.itemcasino.menu.UpgraderMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CasinoMenus {

    public static final DeferredRegister<MenuType<?>> REGISTER =
            DeferredRegister.create(Registries.MENU, ItemCasino.MOD_ID);

    /**
     * {@link IMenuTypeExtension#create} gives the client constructor a {@code RegistryFriendlyByteBuf}
     * so the block position written at open time survives the round trip. Without it the client menu
     * has no idea which block it belongs to, and every subsequent packet would have to carry the pos.
     */
    public static final DeferredHolder<MenuType<?>, MenuType<UpgraderMenu>> UPGRADER =
            REGISTER.register("upgrader", () -> IMenuTypeExtension.create(UpgraderMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<DiceMenu>> DICE =
            REGISTER.register("predict_the_dice", () -> IMenuTypeExtension.create(DiceMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<BlackjackMenu>> BLACKJACK_TABLE =
            REGISTER.register("blackjack_table", () -> IMenuTypeExtension.create(BlackjackMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<CoinFlipMenu>> COIN_FLIP =
            REGISTER.register("coin_flip", () -> IMenuTypeExtension.create(CoinFlipMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<SlotMachineMenu>> SLOT_MACHINE =
            REGISTER.register("slot_machine", () -> IMenuTypeExtension.create(SlotMachineMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<VaultMenu>> VAULT =
            REGISTER.register("vault", () -> IMenuTypeExtension.create(VaultMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<CashierMenu>> CASHIER =
            REGISTER.register("cashier", () -> IMenuTypeExtension.create(CashierMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MineFieldMenu>> MINE_FIELD =
            REGISTER.register("mine_field", () -> IMenuTypeExtension.create(MineFieldMenu::new));

    private CasinoMenus() {}
}
