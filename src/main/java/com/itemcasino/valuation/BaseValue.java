package com.itemcasino.valuation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A seed value supplied by the {@code itemcasino:base_value} data map.
 *
 * @param value whole value points (micro-units are an implementation detail of the solver)
 * @param fixed when true the recipe graph may never lower this value; use it for items whose
 *              recipe cost is a lie (nether star, dragon egg, elytra)
 */
public record BaseValue(long value, boolean fixed) {

    public static final Codec<BaseValue> CODEC = Codec.either(
            Codec.LONG,
            RecordCodecBuilder.<BaseValue>create(i -> i.group(
                    Codec.LONG.fieldOf("value").forGetter(BaseValue::value),
                    Codec.BOOL.optionalFieldOf("fixed", false).forGetter(BaseValue::fixed)
            ).apply(i, BaseValue::new))
    ).xmap(
            either -> either.map(v -> new BaseValue(v, false), b -> b),
            base -> base.fixed() ? com.mojang.datafixers.util.Either.right(base)
                                 : com.mojang.datafixers.util.Either.left(base.value())
    );
}
