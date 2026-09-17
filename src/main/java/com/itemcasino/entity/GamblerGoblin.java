package com.itemcasino.entity;

import com.itemcasino.ItemCasino;
import com.itemcasino.menu.BlackjackMenu;
import com.itemcasino.menu.DiceMenu;
import com.itemcasino.network.CasinoNetwork;
import com.itemcasino.session.BlackjackSession;
import com.itemcasino.session.CasinoSession;
import com.itemcasino.session.DiceSession;
import com.itemcasino.session.SessionHost;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Half goblin, half leprechaun, entirely a bookmaker.
 *
 * <p>He turns up when somebody throws gold on the ground, takes it as his appearance fee, and
 * offers two games. Right-click for blackjack, crouch and right-click for the dice — he deals both
 * and he is the house for both.
 *
 * <h2>Why he was almost free to build</h2>
 * A casino table is a {@link SessionHost}: something that owns a game, knows who is watching, and
 * says where stray items go. So is the pocket edition. Nothing in that interface mentions blocks,
 * so a mob can be one too — and the goblin deals a hand of blackjack through exactly the same
 * session, menu, screen and anti-duplication guards as the table in the lobby. He is a new host for
 * old games, not a new game.
 *
 * <h2>Why nothing he holds is ever saved</h2>
 * His session lives in memory and dies with him, like the pocket edition's and for the same reason:
 * he cannot outlive the screen in any way that matters. Every path that removes him — closing the
 * menu, killing him, the chunk unloading, the server stopping — runs {@link #settleUp}, which
 * hands back everything he is holding. There is no state to persist, so there is no crash-recovery
 * path to get wrong.
 */
public class GamblerGoblin extends PathfinderMob implements SessionHost {

    /** He gets bored. A goblin standing in a field forever is litter, not an encounter. */
    private static final int PATIENCE_TICKS = 20 * 60 * 5;

    @Nullable private CasinoSession session;
    @Nullable private UUID viewer;
    private int idleTicks;
    private boolean greeted;

    public GamblerGoblin(EntityType<? extends GamblerGoblin> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.32D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.4D));
        goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 10.0F));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    // ------------------------------------------------------------------ playing

    @Override
    public InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.CONSUME;

        idleTicks = 0;
        if (!greeted) {
            greeted = true;
            serverPlayer.displayClientMessage(
                    Component.translatable("itemcasino.goblin.greeting"), false);
        }

        boolean dice = player.isShiftKeyDown();
        // He deals to one player at a time, and the table is theirs until they walk away from it —
        // not merely until they have something in escrow. Taking over as soon as nothing was
        // committed let a second player open the same session and lift the first player's stake
        // straight out of the slot.
        if (viewer != null && !isSeated(player)) {
            if (isStillPlaying(seatedPlayer())) {
                serverPlayer.displayClientMessage(
                        Component.translatable("itemcasino.goblin.busy"), true);
                return InteractionResult.CONSUME;
            }
            // The last player left without closing the screen through us (a crash, say): settle
            // their game onto them before anyone else sits down.
            settleUp(seatedPlayer());
            viewer = null;
        }
        if (session == null || (session.hasLiveWager() == false && wrongGame(dice))) {
            settleUp(seatedPlayer());
            session = dice ? new DiceSession(this) : new BlackjackSession(this);
        }

        viewer = player.getUUID();
        CasinoSession open = session;
        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, who) -> dice
                        ? new DiceMenu(containerId, inventory, open, who)
                        : new BlackjackMenu(containerId, inventory, open, who),
                Component.translatable(dice ? "itemcasino.goblin.dice" : "itemcasino.goblin.cards")),
                buffer -> buffer.writeBoolean(true));
        playSound(SoundEvents.VILLAGER_TRADE, 0.8F, 1.6F);
        return InteractionResult.CONSUME;
    }

    /** Online, looking at this goblin's game, and close enough to still be playing it. */
    private boolean isStillPlaying(@Nullable ServerPlayer player) {
        return player != null && session != null
                && player.containerMenu instanceof com.itemcasino.menu.AbstractCasinoMenu menu
                && menu.session() == session && stillValid(player);
    }

    private boolean wrongGame(boolean dice) {
        return dice != (session instanceof DiceSession);
    }

    // ------------------------------------------------------------------ SessionHost

    /**
     * The goblin's level, as the session sees it.
     *
     * <p>This is deliberately <em>not</em> called {@code level()}. {@link net.minecraft.world.entity.Entity}
     * already has a {@code level()} returning {@link Level}, so naming it that way would make this a
     * covariant override of a method vanilla calls constantly — including from the renderer and from
     * the crash-report builder, on a client where the level is a {@code ClientLevel}. The cast would
     * then blow up in code that has nothing to do with gambling. A host is only ever asked for its
     * level on the server, so the interface gets its own name and vanilla keeps its method.
     */
    @Override
    public ServerLevel hostLevel() {
        return (ServerLevel) level();
    }

    @Override
    public void markDirty() {
        // Nothing to persist: he settles up before he can be saved without one.
    }

    @Override
    public void broadcast(IntFunction<CustomPacketPayload> factory) {
        ServerPlayer player = seatedPlayer();
        if (player == null) return;
        CasinoNetwork.send(player, factory.apply(player.containerMenu.containerId));
    }

    @Nullable
    @Override
    public ServerPlayer seatedPlayer() {
        if (viewer == null || !(level() instanceof ServerLevel serverLevel)) return null;
        return serverLevel.getServer().getPlayerList().getPlayer(viewer);
    }

    @Override
    public boolean isSeated(@Nullable Player player) {
        return player != null && player.getUUID().equals(viewer);
    }

    @Override
    public boolean stillValid(Player player) {
        return isAlive() && !isRemoved() && isSeated(player) && player.distanceToSqr(this) <= 64.0D;
    }

    @Nullable
    @Override
    public UUID seatId(int index) {
        return index == 0 ? viewer : null;
    }

    /** To the player he was dealing to, through the casino mailbox; only the grass if nobody. */
    @Override
    public void dropOverflow(ItemStack stack) {
        if (level() instanceof ServerLevel serverLevel
                && com.itemcasino.player.CasinoMailbox.send(serverLevel.getServer(), viewer, stack.copy())) {
            return;
        }
        if (level() instanceof ServerLevel serverLevel) {
            Containers.dropItemStack(serverLevel, getX(), getY(), getZ(), stack);
        }
    }

    @Override
    public void onViewerClosed(Player player) {
        if (!isSeated(player)) return;
        settleUp(player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
        viewer = null;
    }

    /**
     * Hands back everything he is holding and forgets the game.
     *
     * <p>Every exit runs through here, which is the whole reason nothing he holds needs saving: an
     * unfinished spin is settled on its already-decided outcome first, then the escrow, the payout
     * and anything left in the slot go back to the player, or onto the grass if they are gone.
     */
    private void settleUp(@Nullable ServerPlayer to) {
        if (session == null) return;
        try {
            if (session.gameState().holdsEscrow()) session.forceSettle();
            session.liquidate(to);
        } catch (RuntimeException e) {
            ItemCasino.LOGGER.error("The goblin failed to settle up", e);
        }
        session = null;
    }

    // ------------------------------------------------------------------ life

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (session != null) session.tick();

        boolean busy = session != null && session.hasLiveWager();
        idleTicks = busy || seatedPlayer() != null ? 0 : idleTicks + 1;
        if (idleTicks > PATIENCE_TICKS) vanish();
    }

    /** He leaves the way he arrived, and takes nothing that is not his. */
    private void vanish() {
        settleUp(seatedPlayer());
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 0.8, getZ(),
                    24, 0.4, 0.6, 0.4, 0.02);
            playSound(SoundEvents.ILLUSIONER_MIRROR_MOVE, 0.7F, 1.4F);
        }
        discard();
    }

    @Override
    public void die(DamageSource cause) {
        // Killing the bookmaker does not void the bets he is holding.
        settleUp(seatedPlayer());
        super.die(cause);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide()) settleUp(seatedPlayer());
        super.remove(reason);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return session == null || !session.hasLiveWager();
    }

    @Override
    public boolean isPersistenceRequired() {
        return session != null && session.hasLiveWager();
    }
}
