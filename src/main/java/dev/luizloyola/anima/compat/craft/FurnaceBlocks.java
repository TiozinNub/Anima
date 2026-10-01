package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.craft.Furnace;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;

/** Teaches perception what a furnace looks like — the classifier half of {@link Furnace}. */
public final class FurnaceBlocks {

    private FurnaceBlocks() {
    }

    /** Call once from mod init. */
    public static void register() {
        BlockKinds.register((level, pos, state) ->
                state.is(Blocks.FURNACE) ? Optional.of(Furnace.BLOCK) : Optional.empty());
        GrowthRules.register(Furnace.BLOCK, Furnace.RULE);
    }
}
