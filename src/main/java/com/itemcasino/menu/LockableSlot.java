package com.itemcasino.menu;

import com.itemcasino.valuation.ItemFilter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;

/**
 * The wager slot: sealed for the whole of a live session, and invisible to everyone but the seat.
 *
 * <p>{@code mayPickup} alone is not enough — shift-click, the number-key swap, the drop key and
 * drag-split each take a different route through the menu — so {@code AbstractCasinoMenu} repeats
 * the gate in {@code quickMoveStack} and {@code clicked}.
 */
public class LockableSlot extends Slot {

    private final BooleanSupplier open;
    private final java.util.function.Predicate<ItemStack> accepts;
    /** When false the slot is not drawn, not hovered and not clickable, but still counted. */
    private BooleanSupplier shown = () -> true;

    public LockableSlot(Container container, int index, int x, int y, BooleanSupplier open) {
        this(container, index, x, y, open, stack -> true);
    }

    public LockableSlot(Container container, int index, int x, int y, BooleanSupplier open,
                        java.util.function.Predicate<ItemStack> accepts) {
        super(container, index, x, y);
        this.open = open;
        this.accepts = accepts;
    }

    public boolean isOpen() {
        return open.getAsBoolean() && isActive();
    }

    /**
     * Makes this slot appear only when the condition holds.
     *
     * <p>A menu must have the same number of slots on both sides of the wire, and the client builds
     * its menu before it knows how many chairs the table has. So the chairs that may not exist are
     * always created and simply hidden — vanilla skips an inactive slot when it draws, hovers and
     * picks — rather than changing the slot count, which would leave the two sides disagreeing
     * about what is in which slot.
     */
    public LockableSlot shownWhen(BooleanSupplier condition) {
        this.shown = condition;
        return this;
    }

    @Override
    public boolean isActive() {
        return shown.getAsBoolean() && super.isActive();
    }

    /**
     * Containers are refused here as well as when the wager is valued. Refusing in the slot is what
     * the player feels — the box simply will not go in — and it runs on both sides, since item
     * tags are synced to the client.
     */
    @Override
    public boolean mayPlace(ItemStack stack) {
        return isOpen() && !ItemFilter.holdsItems(stack) && accepts.test(stack) && super.mayPlace(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return isOpen() && super.mayPickup(player);
    }
}
