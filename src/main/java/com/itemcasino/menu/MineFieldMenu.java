package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.MineFieldSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

import javax.annotation.Nullable;

public class MineFieldMenu extends AbstractCasinoMenu {

    public MineFieldMenu(int containerId, Inventory playerInventory,
                           RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, new SimpleContainer(1),
                new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT), null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    public MineFieldMenu(int containerId, Inventory playerInventory, CasinoSession session,
                           Player viewer) {
        this(containerId, playerInventory, session.wagerContainer(),
                serverData(session, viewer), session);
    }

    private MineFieldMenu(int containerId, Inventory playerInventory, Container container,
                            ContainerData data, @Nullable CasinoSession session) {
        super(CasinoMenus.MINE_FIELD.get(), containerId, playerInventory, container, data,
                session, CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
    }

    /** Bit {@code t} set: tile {@code t} has been turned and was safe. */
    public int revealedMask() { return readout(MineFieldSession.READOUT_REVEALED); }

    /** Where the mines were; zero until the board is over. */
    public int mineMask() { return readout(MineFieldSession.READOUT_MINES); }

    public int multiplierPpm() { return readout(MineFieldSession.READOUT_MULTIPLIER); }

    public int nextMultiplierPpm() { return readout(MineFieldSession.READOUT_NEXT); }

    /** The board's token, quoted back with each click. */
    public int token() { return readout(MineFieldSession.READOUT_TOKEN); }

    /** The tile turned last, or -1. */
    public int lastTile() { return (readout(MineFieldSession.READOUT_STATUS) & 0x1F) - 1; }

    /** {@code MineFieldSession.OUTCOME_*}. */
    public int outcome() { return readout(MineFieldSession.READOUT_STATUS) >>> 5 & 0b11; }

    /** The house edge in ppm, to 0.01 %. */
    public int edgePpm() { return (readout(MineFieldSession.READOUT_STATUS) >>> 7 & 0x1FFF) * 100; }

    /** The mine count chosen for this table. */
    public int mines() { return Math.max(1, Math.min(24, option())); }

    /** The stake's worth in thousandths, or -1. */
    public int stakeMilli() { return stakeMilli(0); }

    @Override
    public boolean chipsOnly() { return true; }
}
