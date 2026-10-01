package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A body's hurt immunity, counting down from 20 after a blow lands. Public before 26.3 and private
 * from it, so it is reached the same way on every version. Connector-safe.
 */
@Mixin(Entity.class)
public interface EntityInvulnerableTimeAccessor {

    @Accessor("invulnerableTime")
    int anima$invulnerableTime();
}
