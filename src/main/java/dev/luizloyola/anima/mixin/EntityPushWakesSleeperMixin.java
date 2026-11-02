package dev.luizloyola.anima.mixin;

import dev.luizloyola.anima.compat.agent.Sleeping;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A body shoved in bed wakes (Luiz, 2026-11-02). Vanilla only moves it: the sleeper slides off its
 * bed and sleeps on. Agent bodies only; vanilla's own sleepers keep vanilla's rule.
 *
 * <p>The vector overload, not {@code push(Entity)}: {@code LivingEntity} skips that one for a
 * sleeper, and whoever walks into it moves it through this one.
 */
@Mixin(Entity.class)
abstract class EntityPushWakesSleeperMixin {

    @Inject(method = "push(DDD)V", at = @At("HEAD"))
    private void anima$pushWakesSleeper(double x, double y, double z, CallbackInfo ci) {
        if ((Object) this instanceof LivingEntity self && self instanceof AgentBody
                && !self.level().isClientSide() && self.isSleeping()) {
            Sleeping.getUp(self);
        }
    }
}
