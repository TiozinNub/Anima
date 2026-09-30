package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The melee goal's own interval between blows, as a subclass may set it. Connector-safe. */
@Mixin(MeleeAttackGoal.class)
public interface MeleeAttackGoalInvoker {

    @Invoker("getAttackInterval")
    int anima$attackInterval();
}
