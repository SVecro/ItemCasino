package com.itemcasino.item;

import com.itemcasino.session.GameKind;
import com.itemcasino.session.PocketCasino;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * A casino game you carry.
 *
 * <p>Deliberately the opposite of the tables: no block, no spectators, no persistence. Right-click
 * and the game is yours alone; it ends when you close it. The design consequence worth knowing is
 * that an interrupted pocket game always hands everything back — there is nowhere for an escrow to
 * wait, so it never waits.
 */
public class PocketCasinoItem extends Item {

    private final GameKind kind;

    public PocketCasinoItem(Properties properties, GameKind kind) {
        super(properties);
        this.kind = kind;
    }

    public GameKind kind() { return kind; }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) PocketCasino.open(serverPlayer, kind);
        return InteractionResult.CONSUME;
    }
}
