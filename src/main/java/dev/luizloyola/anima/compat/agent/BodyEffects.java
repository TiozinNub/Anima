package dev.luizloyola.anima.compat.agent;

import dev.luizloyola.anima.core.agent.need.Effects;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * The compat half of {@code Vigor}'s source: what is currently applied to a living body, read as
 * the version-neutral {@link Effects} core declared.
 *
 * <p><b>Good or bad is the game's own answer</b> ({@link MobEffectCategory}), not a table here. An
 * effect that is neither counts as neither — a mod's cosmetic effect should not quietly make a body
 * read as tougher.
 *
 * <p>Live, never cached: a reason list is a reading of the tick it was built on.
 */
public final class BodyEffects {

    private BodyEffects() {
    }

    /** This body's active effects, read fresh on every call. */
    public static Effects of(LivingEntity entity) {
        return () -> {
            List<Effects.Effect> active = new ArrayList<>();
            for (MobEffectInstance instance : entity.getActiveEffects()) {
                MobEffectCategory category = instance.getEffect().value().getCategory();
                if (category == MobEffectCategory.NEUTRAL) {
                    continue;
                }
                active.add(new Effects.Effect(instance.getDescriptionId(),
                        category == MobEffectCategory.BENEFICIAL,
                        instance.getAmplifier() + 1));
            }
            return List.copyOf(active);
        };
    }
}
