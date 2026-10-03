package dev.luizloyola.anima.mixin;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The global mob cap check, which is private on 26.x and package-private on 1.21.11. */
@Mixin(NaturalSpawner.SpawnState.class)
public interface SpawnStateInvoker {

    @Invoker("canSpawnForCategoryGlobal")
    boolean anima$canSpawnGlobal(MobCategory category);
}
