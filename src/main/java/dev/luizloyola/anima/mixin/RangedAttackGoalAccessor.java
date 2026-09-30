package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The bounds of a ranged goal's pause between shots: a witch's potions, a drowned's trident. */
@Mixin(RangedAttackGoal.class)
public interface RangedAttackGoalAccessor {

    @Accessor("attackIntervalMin")
    int anima$attackIntervalMin();

    @Accessor("attackIntervalMax")
    int anima$attackIntervalMax();
}
