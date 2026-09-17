package com.itemcasino.chips;

import com.itemcasino.core.chips.Chips;
import com.itemcasino.registry.CasinoDataComponents;
import com.itemcasino.registry.CasinoItems;
import net.minecraft.world.item.ItemStack;

/**
 * Reading and writing the chips on a card.
 *
 * <p>A card is a bearer instrument: whoever holds it can bet or cash out with it, exactly like the
 * items it stands for. That is also why a balance is only ever changed on the server, on a card the
 * server is holding at that moment — in a table's escrow, or in the cashier's card slot.
 */
public final class ChipCards {

    private ChipCards() {}

    public static boolean isCard(ItemStack stack) {
        return !stack.isEmpty() && stack.is(CasinoItems.CHIP_CARD.get());
    }

    /** The balance in cents; zero for anything that is not a card. */
    public static long balance(ItemStack stack) {
        if (!isCard(stack)) return 0;
        Long cents = stack.get(CasinoDataComponents.CHIPS.get());
        return cents == null ? 0 : Math.max(0, cents);
    }

    /** Whole chips on the card, rounded down: what can be bet. */
    public static long wholeChips(ItemStack stack) {
        return balance(stack) / Chips.CENTS;
    }

    /** Sets the balance on this very stack. Only call on a stack the server is holding. */
    public static void setBalance(ItemStack card, long cents) {
        if (!isCard(card)) return;
        card.set(CasinoDataComponents.CHIPS.get(), Math.max(0, cents));
    }

    public static ItemStack newCard(long cents) {
        ItemStack card = new ItemStack(CasinoItems.CHIP_CARD.get());
        card.set(CasinoDataComponents.CHIPS.get(), Math.max(0, cents));
        return card;
    }
}
