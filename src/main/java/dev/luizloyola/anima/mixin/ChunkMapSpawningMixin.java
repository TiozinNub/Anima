package dev.luizloyola.anima.mixin;

import dev.luizloyola.anima.mod.body.SpawnAnchors;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The chunks round an anchoring body are tried for natural spawns, as those near a player are. */
@Mixin(ChunkMap.class)
abstract class ChunkMapSpawningMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "collectSpawningChunks", at = @At("TAIL"))
    private void anima$bodiesSpawnToo(List<LevelChunk> output, CallbackInfo ci) {
        SpawnAnchors.addSpawnChunks(this.level, output);
    }
}
