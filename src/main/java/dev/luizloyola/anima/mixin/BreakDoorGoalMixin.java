package dev.luizloyola.anima.mixin;

import dev.luizloyola.anima.mod.brain.BreakIns;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A door being battered becomes hearable: vanilla plays the blows as a level event that reaches
 * only players' clients (verified in 26.1.2 bytecode), so a Person indoors was deaf to a zombie
 * breaking in. The goal runs only while a mob is actually breaking a door — on Hard, with
 * {@code mobGriefing} on — so every tick of it is a tick of battering.
 */
@Mixin(BreakDoorGoal.class)
abstract class BreakDoorGoalMixin extends DoorInteractGoal {

    BreakDoorGoalMixin(Mob mob) {
        super(mob);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void anima$heardAtTheDoor(CallbackInfo ci) {
        if (!this.mob.level().isClientSide()) {
            BreakIns.battering(this.mob);
        }
    }
}
