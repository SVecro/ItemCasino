package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.core.game.GameState;
import com.itemcasino.session.CasinoSession;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Shared menu plumbing.
 *
 * <p>The menu no longer knows about blocks at all. It holds a {@link CasinoSession}, which a table
 * in the world and a device in a pocket both provide — that is what lets one screen serve both, and
 * what let the spectator support fall out for free: several menus can point at the same session.
 *
 * <p>{@code DATA_CAN_ACT} is computed per viewer, so the same session tells the seated player their
 * buttons are live and tells everyone else they are watching.
 */
public abstract class AbstractCasinoMenu extends AbstractContainerMenu {

    protected static final int WAGER_SLOT = 0;
    protected static final int INVENTORY_START = 1;
    protected static final int INVENTORY_END = INVENTORY_START + 36;

    private final Container wagerContainer;
    @Nullable private final CasinoSession session;
    protected final ContainerData data;

    protected AbstractCasinoMenu(MenuType<?> type, int containerId, Inventory playerInventory,
                                 Container wagerContainer, ContainerData data,
                                 @Nullable CasinoSession session, int wagerX, int wagerY) {
        super(type, containerId);
        this.wagerContainer = wagerContainer;
        this.data = data;
        this.session = session;

        checkContainerSize(wagerContainer, 1);
        addSlot(new LockableSlot(wagerContainer, 0, wagerX, wagerY,
                () -> gameState().acceptsItems() && ownsBaseSlot(), this::acceptsInSlot));
        addPlayerInventory(playerInventory);
        addDataSlots(data);
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        CasinoLayout.INV_X + column * 18, CasinoLayout.INV_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column,
                    CasinoLayout.INV_X + column * 18, CasinoLayout.HOTBAR_Y));
        }
    }

    /**
     * Whether the base wager slot is this viewer's to fill.
     *
     * <p>Seat 0's, for every table that binds it to seat 0 — which is all of them but blackjack,
     * where the slot is bound to whichever chair the viewer is sitting in so that their own stake is
     * always the one in front of them.
     */
    protected boolean ownsBaseSlot() { return seatIndex() == 0; }

    // ------------------------------------------------------------------ accessors

    @Nullable public CasinoSession session() { return session; }

    /** How many chairs this table has. */
    public int seatCount() {
        return Math.max(1, data.get(AbstractCasinoBlockEntity.DATA_SEATS));
    }

    /** The chair whose turn it is, or -1 when nobody may act. */
    public int turnSeat() {
        return data.get(AbstractCasinoBlockEntity.DATA_TURN) - 1;
    }

    public GameState gameState() {
        int ordinal = data.get(AbstractCasinoBlockEntity.DATA_STATE);
        GameState[] values = GameState.values();
        return (ordinal < 0 || ordinal >= values.length) ? GameState.IDLE : values[ordinal];
    }

    public int oddsPpm() { return data.get(AbstractCasinoBlockEntity.DATA_ODDS_PPM); }

    /**
     * What the wager slot takes. A chip card always; items unless the table plays in chips only.
     * Asked on both sides, so it cannot depend on the session, which the client does not have.
     */
    public boolean acceptsInSlot(ItemStack stack) {
        return !chipsOnly() || com.itemcasino.chips.ChipCards.isCard(stack);
    }

    /** Overridden by the dice and mine field menus, whose tables take chips only. */
    public boolean chipsOnly() { return false; }

    /** The chip bet as the server has it for this viewer, in whole chips. */
    public long betChips() { return Math.max(1, data.get(AbstractCasinoBlockEntity.DATA_BET)); }

    /** The most one chip bet may be at this table; unlimited until the server has said. */
    public long maxBetChips() {
        int max = data.get(AbstractCasinoBlockEntity.DATA_MAX_BET);
        return max > 0 ? max : Long.MAX_VALUE;
    }

    public boolean hasPayout() { return data.get(AbstractCasinoBlockEntity.DATA_HAS_PAYOUT) != 0; }

    public int spinTicks() { return data.get(AbstractCasinoBlockEntity.DATA_SPIN_TICKS); }

    /** False for a spectator: they see everything and touch nothing. */
    public boolean canAct() { return data.get(AbstractCasinoBlockEntity.DATA_CAN_ACT) != 0; }

    /** Which seat this viewer holds, or -1. Sent as index+1 so the int channel can say "none". */
    public int seatIndex() { return data.get(AbstractCasinoBlockEntity.DATA_SEAT) - 1; }

    /** The game's own setting, as the server has it: the dice bet, for instance. */
    public int option() { return data.get(AbstractCasinoBlockEntity.DATA_OPTION); }

    /**
     * What a seat has staked, in thousandths of a unit, or -1 for nothing.
     *
     * <p>Priced by the server rather than worked out here. The client's advisory value table only
     * lists items that can be Upgrader targets, so a client-side estimate would read zero for
     * perfectly good stakes — and in a duel the number on screen is the only thing telling a player
     * whether they are about to be robbed.
     */
    public int stakeMilli(int seat) {
        return data.get(seat == 1 ? AbstractCasinoBlockEntity.DATA_STAKE_B
                : AbstractCasinoBlockEntity.DATA_STAKE_A);
    }

    /**
     * The slot machine's three faces, packed, or -1 when it is idle.
     *
     * <p>Carried on the data channel as well as in the result packet so that someone who walks up
     * to a machine already spinning sees the reels stop on the right faces, instead of watching
     * them turn forever because they missed the packet that said where to stop.
     */
    public int reelState() { return data.get(AbstractCasinoBlockEntity.DATA_REELS); }

    /** How many game-specific read-outs the data channel carries. */
    public static final int READOUTS = AbstractCasinoBlockEntity.DATA_AUX_F - AbstractCasinoBlockEntity.DATA_AUX_A + 1;

    /** A game-specific read-out: see each game's menu for what the slots mean. */
    public int readout(int index) {
        return data.get(AbstractCasinoBlockEntity.DATA_AUX_A + Math.max(0, Math.min(READOUTS - 1, index)));
    }

    public ItemStack wagerStack() { return wagerContainer.getItem(0); }

    /**
     * The index of the slot this client's own stake goes in. One for every game but the duel, where
     * it depends which chair you took — and getting it wrong would show you your opponent's stack
     * as the ghost of your own.
     */
    public int ownWagerSlot() { return WAGER_SLOT; }

    /** The stack in this client's own wager slot. */
    public ItemStack ownWagerStack() {
        int index = ownWagerSlot();
        return index >= 0 && index < slots.size() ? slots.get(index).getItem() : ItemStack.EMPTY;
    }

    /**
     * The slot this viewer may wager into, or -1 when they have none.
     *
     * <p>One for every game but the duel, where the two players own one slot each and neither may
     * touch the other's.
     */
    protected int wagerSlotFor(Player player) {
        return canAct() ? WAGER_SLOT : -1;
    }

    // ------------------------------------------------------------------ vanilla contract

    @Override
    public boolean stillValid(Player player) {
        return session == null || session.stillValid(player);
    }

    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType clickType,
                        Player player) {
        // Asking the slot itself, rather than comparing against a fixed index, is what lets a table
        // have two of them. A sealed slot refuses here as well as in mayPlace because shift-click,
        // the number-key swap, the drop key and drag-split each take a different route in.
        if (slotId >= 0 && slotId < slots.size()
                && slots.get(slotId) instanceof LockableSlot lockable && !lockable.isOpen()) {
            // The client has already predicted this click locally. Refusing it silently would leave
            // the two sides disagreeing about what is in the slot, and an inventory the client and
            // server disagree about is how duplication starts.
            sendAllDataToRemote();
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (!gameState().acceptsItems() || !canAct()) return ItemStack.EMPTY;

        int mine = wagerSlotFor(player);
        if (mine < 0) return ItemStack.EMPTY;

        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack raw = slot.getItem();
        ItemStack original = raw.copy();

        if (index == mine) {
            if (!moveItemStackTo(raw, INVENTORY_START, INVENTORY_END, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(raw, original);
        } else if (index >= INVENTORY_START && index < INVENTORY_END) {
            if (!moveItemStackTo(raw, mine, mine + 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;   // somebody else's wager slot
        }

        if (raw.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, raw);
        return original;
    }

    /**
     * Closing the screen never cancels a running session. On a table the seat is released only if
     * nothing is pending; on a pocket device the session is settled and handed back.
     */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (session != null && !player.level().isClientSide()) session.onMenuClosed(player);
    }

    /** Per-viewer view of one session: identical for everyone except {@code DATA_CAN_ACT}. */
    protected static ContainerData serverData(CasinoSession session, Player viewer) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case AbstractCasinoBlockEntity.DATA_STATE -> session.gameState().ordinal();
                    case AbstractCasinoBlockEntity.DATA_ODDS_PPM -> session.frozenPpm();
                    case AbstractCasinoBlockEntity.DATA_HAS_PAYOUT -> session.hasPayout() ? 1 : 0;
                    case AbstractCasinoBlockEntity.DATA_SPIN_TICKS -> session.spinTicks();
                    case AbstractCasinoBlockEntity.DATA_CAN_ACT -> session.isSeated(viewer) ? 1 : 0;
                    case AbstractCasinoBlockEntity.DATA_SEAT -> session.seatIndex(viewer) + 1;
                    case AbstractCasinoBlockEntity.DATA_OPTION -> session.option(viewer);
                    case AbstractCasinoBlockEntity.DATA_STAKE_A -> session.stakeMilli(0);
                    case AbstractCasinoBlockEntity.DATA_STAKE_B -> session.stakeMilli(1);
                    case AbstractCasinoBlockEntity.DATA_REELS -> session.reelState();
                    case AbstractCasinoBlockEntity.DATA_AUX_A -> session.readout(0);
                    case AbstractCasinoBlockEntity.DATA_AUX_B -> session.readout(1);
                    case AbstractCasinoBlockEntity.DATA_AUX_C -> session.readout(2);
                    case AbstractCasinoBlockEntity.DATA_AUX_D -> session.readout(3);
                    case AbstractCasinoBlockEntity.DATA_AUX_E -> session.readout(4);
                    case AbstractCasinoBlockEntity.DATA_AUX_F -> session.readout(5);
                    case AbstractCasinoBlockEntity.DATA_BET ->
                            (int) Math.min(Integer.MAX_VALUE, session.betChipsFor(viewer));
                    case AbstractCasinoBlockEntity.DATA_MAX_BET ->
                            (int) Math.min(Integer.MAX_VALUE, session.maxBetChips());
                    case AbstractCasinoBlockEntity.DATA_STAKE_C -> session.stakeMilli(2);
                    case AbstractCasinoBlockEntity.DATA_TURN -> session.turnSeat() + 1;
                    case AbstractCasinoBlockEntity.DATA_SEATS -> session.seats();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // Server authoritative: the client may never write into this.
            }

            @Override
            public int getCount() {
                return AbstractCasinoBlockEntity.DATA_COUNT;
            }
        };
    }
}
