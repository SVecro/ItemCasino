package com.itemcasino.menu;

import com.itemcasino.CasinoConfig;
import com.itemcasino.ItemCasino;
import com.itemcasino.chips.ChipCards;
import com.itemcasino.core.chips.Chips;
import com.itemcasino.core.value.Fixed;
import com.itemcasino.player.CasinoMailbox;
import com.itemcasino.registry.CasinoBlocks;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.valuation.ItemFilter;
import com.itemcasino.valuation.StackValuator;
import com.itemcasino.valuation.ValuationEngine;
import com.itemcasino.valuation.ValuationSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The cashier's counter: a card slot, a deposit slot, and the player's inventory.
 *
 * <p>Both slots belong to this menu alone and are emptied back into the player's inventory when it
 * closes, the way a crafting grid is. Every exchange is priced by the server when the button is
 * pressed, from the live value table; what the screen shows beforehand is a preview.
 */
public class CashierMenu extends AbstractContainerMenu {

    public static final int CARD_SLOT = 0;
    public static final int DEPOSIT_SLOT = 1;
    private static final int INVENTORY_START = 2;
    private static final int INVENTORY_END = INVENTORY_START + 36;

    public static final int CARD_X = 20;
    public static final int CARD_Y = 30;
    public static final int DEPOSIT_X = 20;
    public static final int DEPOSIT_Y = 62;

    /** The most items one withdrawal may hand over: a double chest's worth of stacks. */
    public static final int MAX_WITHDRAW = 64 * 54;

    private static final int DATA_DEPOSIT_CENTS = 0;
    private static final int DATA_DEPOSIT_STATE = 1;
    public static final int DEPOSIT_OK = 0;
    public static final int DEPOSIT_EMPTY = 1;
    public static final int DEPOSIT_REFUSED = 2;
    public static final int DEPOSIT_CARD = 3;

    /** One currency the cashier pays out, with its unit value when the counter was opened. */
    public record Rate(Item item, long unitValue) {}

    /** What the screen needs to price withdrawals: the fee, and each currency's unit value. */
    public record Rates(int feePpm, List<Rate> rates) {

        public static Rates current() {
            ValuationSnapshot snapshot = ValuationEngine.snapshot();
            List<Rate> rates = new ArrayList<>();
            for (String raw : CasinoConfig.SERVER.cashierCurrencies.get()) {
                if (rates.size() >= 8) break;
                Identifier id = Identifier.tryParse(raw);
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item == null || item == Items.AIR) continue;
                long unit = StackValuator.unitValue(new ItemStack(item), snapshot);
                if (unit == Fixed.INF || unit <= 0) continue;
                rates.add(new Rate(item, unit));
            }
            return new Rates(CasinoConfig.SERVER.cashierFeePpm.get(), List.copyOf(rates));
        }

        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(feePpm);
            buffer.writeVarInt(rates.size());
            for (Rate rate : rates) {
                buffer.writeIdentifier(BuiltInRegistries.ITEM.getKey(rate.item()));
                buffer.writeVarLong(rate.unitValue());
            }
        }

        public static Rates read(RegistryFriendlyByteBuf buffer) {
            int fee = buffer.readVarInt();
            int size = Math.min(8, buffer.readVarInt());
            List<Rate> rates = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Item item = BuiltInRegistries.ITEM.getValue(buffer.readIdentifier());
                long unit = buffer.readVarLong();
                if (item != null && item != Items.AIR) rates.add(new Rate(item, unit));
            }
            return new Rates(fee, List.copyOf(rates));
        }
    }

    private final Container counter;
    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final Rates rates;
    private final boolean server;

    /** Client constructor. */
    public CashierMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, ContainerLevelAccess.NULL, Rates.read(buffer), false);
    }

    /** Server constructor. */
    public CashierMenu(int containerId, Inventory inventory, ContainerLevelAccess access, Rates rates) {
        this(containerId, inventory, access, rates, true);
    }

    private CashierMenu(int containerId, Inventory inventory, ContainerLevelAccess access, Rates rates,
                        boolean server) {
        super(CasinoMenus.CASHIER.get(), containerId);
        this.access = access;
        this.rates = rates;
        this.server = server;
        this.counter = new SimpleContainer(2) {
            @Override
            public void setChanged() {
                super.setChanged();
                CashierMenu.this.slotsChanged(this);
            }
        };
        this.data = new SimpleContainerData(2);

        addSlot(new Slot(counter, CARD_SLOT, CARD_X, CARD_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return ChipCards.isCard(stack);
            }
        });
        addSlot(new Slot(counter, DEPOSIT_SLOT, DEPOSIT_X, DEPOSIT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return !ItemFilter.holdsItems(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        CasinoLayout.INV_X + column * 18, CasinoLayout.INV_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, CasinoLayout.INV_X + column * 18, CasinoLayout.HOTBAR_Y));
        }
        addDataSlots(data);
        updatePreview();
    }

    public Rates rates() { return rates; }

    public ItemStack card() { return counter.getItem(CARD_SLOT); }

    public ItemStack depositStack() { return counter.getItem(DEPOSIT_SLOT); }

    /** What the deposit slot would credit, in cents; meaningful when {@link #depositState} is OK. */
    public int depositPreviewCents() { return data.get(DATA_DEPOSIT_CENTS); }

    public int depositState() { return data.get(DATA_DEPOSIT_STATE); }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        updatePreview();
    }

    /** Priced on the server only: the client has no value table for arbitrary items. */
    private void updatePreview() {
        if (!server) return;
        ItemStack stack = depositStack();
        if (stack.isEmpty()) {
            data.set(DATA_DEPOSIT_STATE, DEPOSIT_EMPTY);
            data.set(DATA_DEPOSIT_CENTS, 0);
        } else if (ChipCards.isCard(stack)) {
            data.set(DATA_DEPOSIT_STATE, DEPOSIT_CARD);
            data.set(DATA_DEPOSIT_CENTS, (int) Math.min(Integer.MAX_VALUE, ChipCards.balance(stack)));
        } else {
            long cents = depositCents(stack, ValuationEngine.snapshot());
            data.set(DATA_DEPOSIT_STATE, cents > 0 ? DEPOSIT_OK : DEPOSIT_REFUSED);
            data.set(DATA_DEPOSIT_CENTS, (int) Math.min(Integer.MAX_VALUE, Math.max(0, cents)));
        }
    }

    /** The chips a stack is exchanged for, or 0 when the cashier will not take it. */
    private static long depositCents(ItemStack stack, ValuationSnapshot snapshot) {
        if (StackValuator.reject(stack, snapshot) != null) return 0;
        long value = StackValuator.value(stack, snapshot);
        return value == Fixed.INF ? 0 : Chips.centsForValue(value);
    }

    // ------------------------------------------------------------------ actions (server)

    /** Everything in the deposit slot onto the card: a stack at its full value, or another card's chips. */
    public void deposit(ServerPlayer player) {
        ItemStack stack = depositStack();
        if (stack.isEmpty()) return;
        long credit;
        if (ChipCards.isCard(stack)) {
            credit = ChipCards.balance(stack);
            if (card().isEmpty()) {
                // No card yet: this one becomes it, with nothing changed.
                counter.setItem(CARD_SLOT, stack.copy());
                counter.setItem(DEPOSIT_SLOT, ItemStack.EMPTY);
                return;
            }
        } else {
            ValuationSnapshot snapshot = ValuationEngine.snapshot();
            StackValuator.Rejection rejection = StackValuator.reject(stack, snapshot);
            if (rejection != null) {
                player.displayClientMessage(Component.translatable(rejection.translationKey()), true);
                return;
            }
            credit = depositCents(stack, snapshot);
            if (credit <= 0) {
                player.displayClientMessage(Component.translatable("itemcasino.reject.too_cheap"), true);
                return;
            }
        }
        ItemStack card = card().isEmpty() ? ChipCards.newCard(0) : card().copy();
        ChipCards.setBalance(card, Chips.add(ChipCards.balance(card), credit));
        // The stack goes and the credit lands in the same call: nothing between them to interrupt.
        counter.setItem(DEPOSIT_SLOT, ItemStack.EMPTY);
        counter.setItem(CARD_SLOT, card);
        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.LOGGER.info("[cashier] {} deposited {} for {} chip cents (balance {})",
                    player.getName().getString(), stack, credit, ChipCards.balance(card));
        }
    }

    /**
     * Buys {@code count} of a currency with the card's chips, at the live value plus the fee. Refused
     * whole when the card cannot cover it: a withdrawal never half-happens.
     */
    public void withdraw(ServerPlayer player, int index, int count) {
        if (index < 0 || index >= rates.rates().size() || count <= 0) return;
        ItemStack card = card();
        if (!ChipCards.isCard(card)) return;
        Item item = rates.rates().get(index).item();
        // Priced now, not at the moment the screen was opened.
        long unit = StackValuator.unitValue(new ItemStack(item), ValuationEngine.snapshot());
        if (unit == Fixed.INF || unit <= 0) return;
        int wanted = Math.min(count, MAX_WITHDRAW);
        long value = unit >= Long.MAX_VALUE / wanted ? Long.MAX_VALUE : unit * wanted;
        long cost = Chips.withdrawCost(value, CasinoConfig.SERVER.cashierFeePpm.get());
        long balance = ChipCards.balance(card);
        if (cost <= 0 || cost > balance) {
            player.displayClientMessage(Component.translatable("itemcasino.reject.not_enough_chips"), true);
            return;
        }
        ItemStack updated = card.copy();
        ChipCards.setBalance(updated, balance - cost);
        counter.setItem(CARD_SLOT, updated);

        List<ItemStack> stacks = new ArrayList<>();
        int max = new ItemStack(item).getMaxStackSize();
        for (int left = wanted; left > 0; left -= max) stacks.add(new ItemStack(item, Math.min(max, left)));
        // Inventory first; what does not fit waits in the casino mailbox rather than on the floor.
        CasinoMailbox.send(player.level().getServer(), player.getUUID(), stacks);
        if (CasinoConfig.SERVER.logSettlements.get()) {
            ItemCasino.LOGGER.info("[cashier] {} withdrew {}x{} for {} chip cents (balance {})",
                    player.getName().getString(), wanted, BuiltInRegistries.ITEM.getKey(item), cost,
                    balance - cost);
        }
    }

    // ------------------------------------------------------------------ vanilla contract

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, pos) -> clearContainer(player, counter));
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, CasinoBlocks.CASHIER.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack raw = slot.getItem();
        ItemStack original = raw.copy();
        if (index == CARD_SLOT || index == DEPOSIT_SLOT) {
            if (!moveItemStackTo(raw, INVENTORY_START, INVENTORY_END, true)) return ItemStack.EMPTY;
        } else if (ChipCards.isCard(raw) && card().isEmpty()) {
            if (!moveItemStackTo(raw, CARD_SLOT, CARD_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(raw, DEPOSIT_SLOT, DEPOSIT_SLOT + 1, false)) {
            return ItemStack.EMPTY;
        }
        if (raw.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, raw);
        return original;
    }
}
