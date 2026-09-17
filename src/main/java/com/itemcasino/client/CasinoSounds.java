package com.itemcasino.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * The noises a casino makes.
 *
 * <p>All of it is built from vanilla sounds on purpose — a resource pack of custom audio is a lot
 * of weight for a handful of clicks, and the vanilla set already contains a convincing wooden tick.
 * What sells a wheel or a reel is not the sample, it is the <em>rhythm</em>: a detent every time the
 * thing passes a notch, so the ticking spaces out by itself as the easing slows. Nothing here
 * schedules a "slow down" — the sound follows the animation, and the animation follows the server.
 */
public final class CasinoSounds {

    private CasinoSounds() {}

    /** One notch of a spinning wheel. Pitch drifts with the detent so it does not read as a loop. */
    public static void wheelTick(int detent) {
        play(SoundEvents.NOTE_BLOCK_HAT.value(), 0.22F, 1.4F + (detent % 3) * 0.06F);
    }

    /** A symbol passing the payline on a reel. Higher for each reel along, left to right. */
    public static void reelTick(int reel) {
        play(SoundEvents.NOTE_BLOCK_HAT.value(), 0.18F, 1.5F + reel * 0.12F);
    }

    /** A reel coming to rest: heavier than the ticks it interrupts. */
    public static void reelStop(int reel) {
        play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.45F, 0.9F + reel * 0.18F);
    }

    /** A card landing on the felt. */
    public static void card() {
        play(SoundEvents.ITEM_PICKUP, 0.5F, 1.7F);
    }

    /** A safe tile on the mine field: a chime that climbs with every tile survived. */
    public static void safeTile(int survived) {
        play(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.5F, 0.8F + Math.min(survived, 24) * 0.05F);
    }

    /** A mine. */
    public static void boom() {
        play(SoundEvents.GENERIC_EXPLODE.value(), 0.35F, 1.3F);
    }

    public static void win(boolean big) {
        play(big ? SoundEvents.PLAYER_LEVELUP : SoundEvents.NOTE_BLOCK_BELL.value(),
                big ? 1.0F : 0.6F, big ? 1.0F : 1.4F);
    }

    /** The Vault's pot: the loudest thing the casino does. */
    public static void jackpot() {
        play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
    }

    public static void lose() {
        play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.7F);
    }

    private static void play(SoundEvent event, float volume, float pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.player.playSound(event, volume, pitch);
    }
}
