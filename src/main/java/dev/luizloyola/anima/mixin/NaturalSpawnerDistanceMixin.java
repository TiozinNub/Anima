package dev.luizloyola.anima.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.anima.mod.body.SpawnAnchors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A spawn position is measured from the nearest anchor, a player or a body, so the 24-block
 * exclusion and the despawn-distance checks hold round a body as round a player.
 */
@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerDistanceMixin {

    private static final String SPAWN_AT =
            "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
                    + "Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
                    + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V";

    /** With no player in the level, a body still lets the position be weighed. */
    @Definition(id = "nearestPlayer", local = @Local(type = Player.class))
    @Expression("nearestPlayer == null")
    @ModifyExpressionValue(method = SPAWN_AT, at = @At("MIXINEXTRAS:EXPRESSION"))
    private static boolean anima$noAnchor(boolean noPlayer, @Local(argsOnly = true) ServerLevel level) {
        return noPlayer && SpawnAnchors.of(level).isEmpty();
    }

    @WrapOperation(method = SPAWN_AT, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;distanceToSqr(DDD)D"))
    private static double anima$nearestAnchor(Player player, double x, double y, double z,
            Operation<Double> original, @Local(argsOnly = true) ServerLevel level) {
        double body = SpawnAnchors.nearestSq(level, x, y, z);
        return player == null ? body : Math.min(original.call(player, x, y, z), body);
    }
}
