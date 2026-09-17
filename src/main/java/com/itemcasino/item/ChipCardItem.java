package com.itemcasino.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Chip Card: an ordinary item whose balance lives in the {@code itemcasino:chips} component.
 *
 * <p>Its own class for one reason: a dropped card stays on the ground for an hour instead of the
 * usual five minutes, so a card lost at death or knocked out of an inventory can still be walked
 * back to. Fire resistance comes from its properties.
 */
public class ChipCardItem extends Item {

    /** An hour, in ticks. */
    private static final int LIFESPAN_TICKS = 20 * 60 * 60;

    public ChipCardItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getEntityLifespan(ItemStack stack, Level level) {
        return LIFESPAN_TICKS;
    }
}
