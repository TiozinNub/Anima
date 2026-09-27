package dev.luizloyola.anima.mixin;

import dev.luizloyola.anima.mod.brain.PlaceMarks;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game-event choke point: every world happening with a source rides through here, so it is
 * the one place to stamp perception marks server-wide (radius-free, unlike a body's ear). It feeds
 * {@link PlaceMarks} from BLOCK_PLACE, which is what lets a watching body tell a placing swing
 * from a mining one.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelGameEventMixin {

    @Inject(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
            at = @At("HEAD"))
    private void anima$perceptionMarks(Holder<GameEvent> event, Vec3 pos,
                                       GameEvent.Context context, CallbackInfo ci) {
        if (event.is(GameEvent.BLOCK_PLACE)
                && context.sourceEntity() instanceof LivingEntity body) {
            PlaceMarks.mark(body.getUUID(), ((ServerLevel) (Object) this).getGameTime());
        }
    }
}
