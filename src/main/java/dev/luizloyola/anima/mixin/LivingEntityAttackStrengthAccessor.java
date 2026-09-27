package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the attack-strength counter vanilla keeps on every living entity and advances only for a
 * player. An agent's fighting arm runs the same counter with the same resets, so its charge is a
 * player's charge by construction rather than a copy kept beside it.
 *
 * <p>An {@code @Accessor} and nothing else — deliberate for Connector.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAttackStrengthAccessor {

    @Accessor("attackStrengthTicker")
    int anima$attackStrengthTicker();

    @Accessor("attackStrengthTicker")
    void anima$setAttackStrengthTicker(int ticks);
}
