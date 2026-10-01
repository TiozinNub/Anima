package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The blow that opened a body's hurt immunity: inside the window, only a bigger one lands, and only
 * what it has over this. An {@code @Accessor} and nothing else — deliberate for Connector.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityLastHurtAccessor {

    @Accessor("lastHurt")
    float anima$lastHurt();
}
