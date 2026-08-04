package dev.luizloyola.anima.mixin;

import dev.luizloyola.anima.mod.brain.PlaceIndexes;
import dev.luizloyola.anima.mod.brain.RegionCaches;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The block-change choke point. Fabric publishes no general "a block changed" event — only
 * player-caused breaking — and {@link RegionCaches} needs every one of them.
 *
 * <p>The four-argument {@code setBlock} is the bottom of the funnel: the three-argument one
 * delegates to it, and {@code destroyBlock}, {@code removeBlock}, {@code setBlockAndUpdate} and
 * every command and piston arrive through those two. It is byte-identical across every version
 * this mod targets, hence no Stonecutter comment.
 *
 * <p>At HEAD, and indifferent to the return value: over-forgetting is free, under-forgetting is
 * the lone-stump bug.
 */
@Mixin(Level.class)
abstract class LevelSetBlockMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void anima$forgetShapeHere(BlockPos pos, BlockState state, int flags, int recursion,
                                       CallbackInfoReturnable<Boolean> cir) {
        RegionCaches.onBlockChanged((Level) (Object) this, pos);
        PlaceIndexes.onBlockChanged((Level) (Object) this, pos);
    }
}
