package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The bounds of a ranged goal's pause between shots: a witch's potions, a drowned's trident. 26.4
 * made them suppliers, read afresh at each shot.
 */
@Mixin(RangedAttackGoal.class)
public interface RangedAttackGoalAccessor {

    //? if >=26.4-alpha {
    /*@Accessor("attackIntervalMin")
    java.util.function.IntSupplier anima$attackIntervalMin();

    @Accessor("attackIntervalMax")
    java.util.function.IntSupplier anima$attackIntervalMax();
    *///?} else {
    @Accessor("attackIntervalMin")
    int anima$attackIntervalMin();

    @Accessor("attackIntervalMax")
    int anima$attackIntervalMax();
    //?}
}
