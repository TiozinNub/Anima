package dev.luizloyola.anima.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.luizloyola.anima.mod.body.SpawnAnchors;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.TriState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The global mob cap grows by the chunks bodies spawn in, as it grows by a player's: a cap of
 * {@code max x chunks / 289}, so with no player online it would otherwise be 0.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheSpawnCountMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private DistanceManager distanceManager;

    // The overload, named: a bare "tickChunks" picks the no-argument one, which has no call.
    //? if >=26.3 {
    /*private static final String TICK = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V";
    *///?} else {
    private static final String TICK = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V";
    //?}

    @ModifyExpressionValue(method = TICK,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/DistanceManager;getNaturalSpawnChunkCount()I"))
    private int anima$countBodyChunks(int players) {
        return players + SpawnAnchors.extraChunkCount(this.level,
                //? if >=26.1 {
                pos -> this.distanceManager.hasPlayersNearby(pos.pack()) != TriState.FALSE);
                //?} else {
                /*pos -> this.distanceManager.hasPlayersNearby(pos.toLong()) != TriState.FALSE);
                *///?}
    }
}
