package com.itemcasino.session;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.IntFunction;

/**
 * Whatever owns a {@link CasinoSession}: a table in the world, or a device in someone's pocket.
 *
 * <p>The session holds the rules, the escrow and the state machine. The host answers the four
 * questions the session cannot: where am I, who is watching, where do leftovers go, and how do I
 * persist. Splitting it this way is what lets the same wager logic run on a block that a whole
 * server can crowd around and on an item only its holder can see.
 */
public interface SessionHost {

    /**
     * The server level this host lives in.
     *
     * <p>Named {@code hostLevel} rather than {@code level} on purpose. A host was once an entity
     * (a wandering dealer, since removed), and {@code Entity} already declares
     * {@code level()}: a same-named method here became a covariant override that vanilla called on
     * the client too — where the level is a {@code ClientLevel} and the narrowing cast blew up
     * inside the renderer. A host is only ever asked this on the server, so the interface keeps a
     * name vanilla will never collide with, and the next entity host is safe by construction.

     */
    ServerLevel hostLevel();

    /**
     * Where outcomes come from. Never the level's own random source: see {@link CasinoRandom}.
     */
    default RandomSource random() {
        return CasinoRandom.INSTANCE;
    }

    /** Marks the owner dirty so the session's state reaches disk. */
    void markDirty();

    /**
     * Sends a packet to everyone who should see this session.
     *
     * <p>The factory takes the container id because it differs per viewer: two players watching the
     * same table each have their own menu, and a payload addressed to the wrong id is discarded by
     * the receiving client — which is exactly what makes the id worth validating in the first place.
     */
    void broadcast(IntFunction<CustomPacketPayload> factory);

    /** The player holding the seat, if they are online and still looking at this session. */
    @Nullable
    ServerPlayer seatedPlayer();

    /** The player in one particular seat. Only a duel has a seat other than zero. */
    @Nullable
    default ServerPlayer seatedPlayer(int index) {
        return index == 0 ? seatedPlayer() : null;
    }

    /**
     * Who holds a seat, whether or not they are online or looking at the table right now.
     *
     * <p>This is the answer to "whose items are these". {@link #seatedPlayer(int)} is the answer to
     * "who can I send a packet to", and it is null the moment the player closes the screen — which
     * is exactly why it must never decide ownership. A duel once took its winner from it, so a
     * duellist who readied and closed the screen lost the pot to the other chair.
     */
    @Nullable
    default java.util.UUID seatId(int index) {
        ServerPlayer player = seatedPlayer(index);
        return player == null ? null : player.getUUID();
    }

    /** True when this player may act: place a wager, pick a target, hit or stand. */
    boolean isSeated(@Nullable Player player);

    /**
     * Which seat this player holds, or -1 for a spectator.
     *
     * <p>Every game but the duel has exactly one seat, so the default answer is the only one it
     * could be. Coin Flip overrides the host side of this to tell the two duellists apart, which is
     * what lets one slot belong to each of them.
     */
    default int seatIndex(@Nullable Player player) {
        return isSeated(player) ? 0 : -1;
    }

    /** Still reachable? A broken block or a discarded pocket device answers no. */
    boolean stillValid(Player player);

    /** Last resort for items that fit nowhere: the world floor, or the player's feet. */
    void dropOverflow(ItemStack stack);

    /** A viewer closed their screen. A table frees its seat; a pocket device ends its session. */
    void onViewerClosed(Player player);
}
