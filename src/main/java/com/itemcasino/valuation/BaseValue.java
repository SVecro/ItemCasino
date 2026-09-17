package com.itemcasino.valuation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A seed value supplied by the {@code itemcasino:base_value} data map.
 *
 * <p>Written either as a bare number, {@code "minecraft:cobblestone": 1}, or as an object when it is
 * pinned, {@code "minecraft:diamond": {"points": 256, "fixed": true}}.
 *
 * <p>The object's field is {@code points}, never {@code value}. NeoForge wraps every data map entry
 * in an optional {@code {"value": ..., "replace": ...}} object and tries that form first, so an
 * entry written {@code {"value": 256, "fixed": true}} is read as the wrapper around a bare 256 and
 * its {@code fixed} is silently dropped. That is exactly what happened to every pinned value until
 * the field was renamed; {@code tools/offline/static_checks.py} now refuses the old spelling.
 *
 * @param value whole value points (micro-units are an implementation detail of the solver)
 * @param fixed when true the recipe graph may never lower this value; use it for items whose
 *              recipe cost is a lie (nether star, dragon egg, elytra). Never for an item a recipe
 *              duplicates: pinned above what the duplication costs, every craft creates value.
 */
public record BaseValue(long value, boolean fixed) {

    public static final Codec<BaseValue> CODEC = Codec.either(
            Codec.LONG,
            RecordCodecBuilder.<BaseValue>create(i -> i.group(
                    Codec.LONG.fieldOf("points").forGetter(BaseValue::value),
                    Codec.BOOL.optionalFieldOf("fixed", false).forGetter(BaseValue::fixed)
            ).apply(i, BaseValue::new))
    ).xmap(
            either -> either.map(v -> new BaseValue(v, false), b -> b),
            base -> base.fixed() ? com.mojang.datafixers.util.Either.right(base)
                                 : com.mojang.datafixers.util.Either.left(base.value())
    );
}
