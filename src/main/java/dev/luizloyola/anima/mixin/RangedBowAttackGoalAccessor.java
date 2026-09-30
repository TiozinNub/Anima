package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The pause a bow goal takes between shots, which a skeleton sets from the world's difficulty. */
@Mixin(RangedBowAttackGoal.class)
public interface RangedBowAttackGoalAccessor {

    @Accessor("attackIntervalMin")
    int anima$attackIntervalMin();
}
