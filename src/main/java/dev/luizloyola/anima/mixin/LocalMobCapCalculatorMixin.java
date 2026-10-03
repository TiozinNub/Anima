package dev.luizloyola.anima.mixin;

import dev.luizloyola.anima.core.spawn.Anchors;
import dev.luizloyola.anima.mod.body.BodyGrant;
import dev.luizloyola.anima.mod.body.SpawnAnchors;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Each anchoring body has a local mob cap, as each player does, at its area's share: a mob counts
 * against every body whose spawn chunks hold it, and a chunk may spawn while any anchor near it is
 * under its cap. Vanilla builds a calculator per tick, so the counts here last a tick too.
 */
@Mixin(LocalMobCapCalculator.class)
abstract class LocalMobCapCalculatorMixin implements BodyGrant {

    @Shadow
    @Final
    private ChunkMap chunkMap;

    @Unique
    private Anchors anima$anchors;

    @Unique
    private int[][] anima$counts;

    @Unique
    private boolean anima$byBody;

    @Override
    public boolean anima$byBody() {
        return anima$byBody;
    }

    @Unique
    private Anchors anima$anchors() {
        if (anima$anchors == null) {
            anima$anchors = SpawnAnchors.of(((ChunkMapLevelAccessor) chunkMap).anima$level());
            anima$counts = new int[anima$anchors.size()][MobCategory.values().length];
        }
        return anima$anchors;
    }

    @Inject(method = "addMob", at = @At("TAIL"))
    private void anima$countForBodies(ChunkPos pos, MobCategory category, CallbackInfo ci) {
        anima$anchors().forEachNear(SpawnAnchors.chunkX(pos), SpawnAnchors.chunkZ(pos),
                i -> anima$counts[i][category.ordinal()]++);
    }

    @Inject(method = "canSpawn", at = @At("RETURN"), cancellable = true)
    private void anima$bodyUnderCap(MobCategory category, ChunkPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        anima$byBody = false;
        if (cir.getReturnValueZ()) {
            return;
        }
        boolean[] under = {false};
        anima$anchors().forEachNear(SpawnAnchors.chunkX(pos), SpawnAnchors.chunkZ(pos), i -> {
            if (anima$counts[i][category.ordinal()] < Anchors.localCap(category.getMaxInstancesPerChunk())) {
                under[0] = true;
            }
        });
        if (under[0]) {
            anima$byBody = true;
            cir.setReturnValue(true);
        }
    }
}
