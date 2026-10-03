package dev.luizloyola.anima.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.luizloyola.anima.mod.body.BodyGrant;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A spawn only a body allows re-checks the global cap, which vanilla checks once at the start of
 * the tick. Round a player's 70 that lets a few extra through; round a body's 6, a Person on the
 * move met 9 to 11 (flown 2026-10-02), since what it outran left its local count.
 */
@Mixin(NaturalSpawner.SpawnState.class)
abstract class SpawnStateGlobalCapMixin {

    @Shadow
    @Final
    private LocalMobCapCalculator localMobCapCalculator;

    @ModifyReturnValue(method = "canSpawnForCategoryLocal", at = @At("RETURN"))
    private boolean anima$bodyKeepsGlobalCap(boolean local, @Local(argsOnly = true) MobCategory category) {
        return local && (!((BodyGrant) localMobCapCalculator).anima$byBody()
                || ((SpawnStateInvoker) (Object) this).anima$canSpawnGlobal(category));
    }
}
