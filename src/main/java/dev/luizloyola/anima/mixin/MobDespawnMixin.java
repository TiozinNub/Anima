package dev.luizloyola.anima.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.anima.mod.body.SpawnAnchors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A mob despawns by its distance to the nearest anchor, a player or a body. Without it, a player
 * online anywhere past 128 blocks removes a mob spawned round a body on its first tick, and with
 * nobody online nothing ever despawns.
 */
@Mixin(Mob.class)
abstract class MobDespawnMixin {

    @Definition(id = "nearest", local = @Local(type = Entity.class))
    @Expression("nearest != null")
    @ModifyExpressionValue(method = "checkDespawn", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean anima$anchorExists(boolean player) {
        return player || ((Mob) (Object) this).level() instanceof ServerLevel level
                && !SpawnAnchors.of(level).isEmpty();
    }

    @WrapOperation(method = "checkDespawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"))
    private double anima$nearestAnchor(Entity player, Entity self, Operation<Double> original) {
        if (!(self.level() instanceof ServerLevel level)) {
            return original.call(player, self);
        }
        double body = SpawnAnchors.nearestSq(level, self.getX(), self.getY(), self.getZ());
        return player == null ? body : Math.min(original.call(player, self), body);
    }
}
