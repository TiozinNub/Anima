package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches a mob's goals, so how often it attacks is read off the goal that does it rather than
 * guessed. An {@code @Accessor} and nothing else — deliberate for Connector.
 */
@Mixin(Mob.class)
public interface MobGoalSelectorAccessor {

    @Accessor("goalSelector")
    GoalSelector anima$goalSelector();
}
