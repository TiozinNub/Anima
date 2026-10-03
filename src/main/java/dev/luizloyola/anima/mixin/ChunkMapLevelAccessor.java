package dev.luizloyola.anima.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level a mob cap counts in, reached through the chunk map it holds. */
@Mixin(ChunkMap.class)
public interface ChunkMapLevelAccessor {

    @Accessor("level")
    ServerLevel anima$level();
}
