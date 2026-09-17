package com.itemcasino.menu;

import com.itemcasino.block.AbstractCasinoBlockEntity;
import com.itemcasino.registry.CasinoMenus;
import com.itemcasino.core.game.Dice;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.DiceSession;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SimpleContainerData;

import javax.annotation.Nullable;

/** Predict the Dice. The bet travels in the option slot; the last roll and the table's limits in the read-outs. */
public class DiceMenu extends AbstractCasinoMenu {

    /**
     * Client constructor. The leading boolean says whether this menu came from a pocket device or
     * a block; the block position that follows is not needed client-side, but reading it keeps the
     * two encodings symmetrical and the buffer fully consumed.
     */
    public DiceMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, new SimpleContainer(1),
                new SimpleContainerData(AbstractCasinoBlockEntity.DATA_COUNT), null);
        if (!buffer.readBoolean()) buffer.readBlockPos();
    }

    /** Server constructor, shared by the table and the pocket device. */
    public DiceMenu(int containerId, Inventory playerInventory, CasinoSession session, Player viewer) {
        this(containerId, playerInventory, session.wagerContainer(),
                serverData(session, viewer), session);
    }

    private DiceMenu(int containerId, Inventory playerInventory,
                  net.minecraft.world.Container container,
                  net.minecraft.world.inventory.ContainerData data,
                  @Nullable CasinoSession session) {
        super(CasinoMenus.DICE.get(), containerId, playerInventory, container, data, session, CasinoLayout.WAGER_X, CasinoLayout.WAGER_Y);
    }

    /** The bet's chance in hundredths of a percent; 48.50 % until the server has said otherwise. */
    public int chance() {
        int chance = Dice.chanceOf(option());
        return chance > 0 ? chance : 4850;
    }

    public boolean over() { return Dice.isOver(option()); }

    /** The last roll (0..9999), or -1 before the first. */
    public int lastRoll() { return readout(DiceSession.READOUT_LAST_ROLL) - 1; }

    public boolean lastWin() { return (readout(DiceSession.READOUT_LAST_FLAGS) & DiceSession.FLAG_WIN) != 0; }

    public boolean lastOver() { return (readout(DiceSession.READOUT_LAST_FLAGS) & DiceSession.FLAG_OVER) != 0; }

    public int edgePpm() { return readout(DiceSession.READOUT_EDGE_PPM); }

    public int minChance() {
        int min = readout(DiceSession.READOUT_LIMITS) & 0xFFFF;
        return min > 0 ? min : 100;
    }

    public int maxChance() {
        int max = readout(DiceSession.READOUT_LIMITS) >>> 16;
        return max > 0 ? max : 9500;
    }

    @Override
    public boolean chipsOnly() { return true; }
}
