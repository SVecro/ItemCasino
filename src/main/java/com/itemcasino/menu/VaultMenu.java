package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.VaultSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

import javax.annotation.Nullable;

public class VaultMenu extends AbstractCasinoMenu {

    public VaultMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, new SimpleContainer(1),
                new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT), null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    public VaultMenu(int containerId, Inventory playerInventory, CasinoSession session,
                     Player viewer) {
        this(containerId, playerInventory, session.wagerContainer(),
                serverData(session, viewer), session);
    }

    private VaultMenu(int containerId, Inventory playerInventory, Container container,
                      ContainerData data, @Nullable CasinoSession session) {
        super(CasinoMenus.VAULT.get(), containerId, playerInventory, container, data, session,
                CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
    }

    /** The pot's worth in thousandths of a value point. */
    public int potMilli() { return stakeMilli(0); }

    /** This offering's worth in thousandths. */
    public int offeringMilli() { return stakeMilli(1); }

    /** The chance this offering buys at the chosen share, in parts per million. */
    public int drawPpm() { return readout(VaultSession.READOUT_DRAW_PPM); }

    /** The share of the pot being played for, in percent. */
    public int sharePercent() { return Math.max(1, Math.min(100, option())); }

    /** What winning would hand over at that share, in thousandths; -1 when unpriced. */
    public int prizeMilli() { return readout(VaultSession.READOUT_PRIZE_MILLI); }

    /** The server's ceiling on a single draw, in parts per million (to 0.01 %). */
    public int maxPpm() { return (readout(VaultSession.READOUT_TERMS) & 0xFFFF) * 100; }

    /** What a draw returns in expectation, as ppm of the offering (to 0.01 %). */
    public int returnPpm() { return (readout(VaultSession.READOUT_TERMS) >>> 16) * 100; }

    /** How much of an offering goes into the pot, as ppm (to 0.01 %). */
    public int potSharePpm() { return readout(VaultSession.READOUT_POT_SHARE) * 100; }
}
