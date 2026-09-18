package com.itemcasino.session;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything one chair at a table owns: its slot, its escrow, its bet setting, its stake, whose
 * money it is, and what it has won and not yet collected.
 *
 * <p><strong>Seat 0 is not one of these.</strong> The first chair's copy of all of it lives in
 * {@link CasinoSession}'s own fields and is saved under the keys it has always used — {@code wager},
 * {@code escrow}, {@code payout}, {@code bet_chips}, {@code stake_cents}, {@code wager_owner}. That
 * asymmetry is deliberate and worth its ugliness twice over: every solo table, the pocket devices
 * and every world already written keep working untouched, and a refactor that moved a live escrow
 * from one field to another is exactly the shape of change that has cost this mod duplicated items
 * before (§8 of the handoff). Chairs beyond the first are new, so they can be new storage.
 *
 * <p>A seat never decides anything. It holds; the session moves things in and out of it, always
 * synchronously, never across a tick boundary, so a stack cannot exist in the slot and the escrow
 * at the same time.
 */
public final class Seat {

    private final SimpleContainer slot;
    /** Set while the session is moving the slot itself, so its own callback cannot re-enter. */
    private boolean suppress;

    private ItemStack escrow = ItemStack.EMPTY;
    /** Whole chips this chair wants to bet when its slot holds a card. A per-chair setting. */
    private long betChips = 10;
    /** The chips this chair's escrow staked, in cents; zero when the stake is items. */
    private long stakeCents;
    /** Who put the stake up, captured at commit. Refunds, stats and payouts all follow this. */
    @Nullable private UUID owner;
    /** What this chair has won and not yet collected. One buffer per chair: three can win at once. */
    private final List<ItemStack> payout = new ArrayList<>();

    Seat(SessionHost host, Runnable onChanged) {
        this.slot = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                host.markDirty();
                if (!suppress) onChanged.run();
            }
        };
    }

    // ------------------------------------------------------------------ the slot

    public SimpleContainer slot() { return slot; }

    public ItemStack stack() { return slot.getItem(0); }

    /**
     * Runs a move of the slot's contents with the slot callback held down.
     *
     * <p>Escrowing and refunding move the stack themselves; letting the callback run during those
     * would re-derive the table's state from a half-applied mutation. Always paired with an
     * explicit re-derivation afterwards by the caller.
     */
    void quietly(Runnable move) {
        suppress = true;
        try {
            move.run();
        } finally {
            suppress = false;
        }
    }

    // ------------------------------------------------------------------ the money

    public ItemStack escrow() { return escrow; }

    void setEscrow(ItemStack stack) { this.escrow = stack; }

    public long betChips() { return betChips; }

    void setBetChips(long chips) { this.betChips = chips; }

    public long stakeCents() { return stakeCents; }

    void setStakeCents(long cents) { this.stakeCents = cents; }

    @Nullable public UUID owner() { return owner; }

    void setOwner(@Nullable UUID owner) { this.owner = owner; }

    public boolean hasPayout() { return !payout.isEmpty(); }

    public List<ItemStack> peekPayout() { return List.copyOf(payout); }

    void addPayout(List<ItemStack> stacks) { payout.addAll(stacks); }

    /** Takes this chair's winnings for delivery elsewhere, leaving the buffer empty. */
    List<ItemStack> takePayout() {
        List<ItemStack> out = new ArrayList<>(payout);
        payout.clear();
        return out;
    }

    /** True while this chair still holds something that belongs to a player. */
    public boolean holdsAnything() {
        return !stack().isEmpty() || !escrow.isEmpty() || !payout.isEmpty();
    }

    /** Forgets the hand that just ended, keeping whatever is still in the slot. */
    void clearWager() {
        escrow = ItemStack.EMPTY;
        stakeCents = 0;
        owner = null;
    }

    // ------------------------------------------------------------------ persistence

    void save(ValueOutput out, String prefix) {
        out.store(prefix + "slot", ItemStack.OPTIONAL_CODEC, stack());
        out.store(prefix + "escrow", ItemStack.OPTIONAL_CODEC, escrow);
        out.store(prefix + "payout", ItemStack.CODEC.listOf(), List.copyOf(payout));
        out.putLong(prefix + "bet_chips", betChips);
        out.putLong(prefix + "stake_cents", stakeCents);
        if (owner != null) out.putString(prefix + "owner", owner.toString());
    }

    void load(ValueInput in, String prefix) {
        quietly(() -> slot.setItem(0,
                in.read(prefix + "slot", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY)));
        escrow = in.read(prefix + "escrow", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        payout.clear();
        in.read(prefix + "payout", ItemStack.CODEC.listOf()).ifPresent(payout::addAll);
        betChips = Math.max(1, in.getLongOr(prefix + "bet_chips", 10L));
        stakeCents = Math.max(0, in.getLongOr(prefix + "stake_cents", 0L));
        owner = in.getString(prefix + "owner").map(CasinoSession::parseUuid).orElse(null);
    }
}
