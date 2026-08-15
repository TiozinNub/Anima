package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.craft.Workbench;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;

/**
 * Teaches perception what a crafting table looks like — the classifier half of
 * {@link Workbench}, wired the way a consumer wires its own kinds (the crops'
 * {@code PatchBlocks} shape), because owning the ladder buys Anima no shortcuts through it.
 */
public final class WorkbenchBlocks {

    private WorkbenchBlocks() {
    }

    /** Call once from mod init. */
    public static void register() {
        BlockKinds.register((level, pos, state) ->
                state.is(Blocks.CRAFTING_TABLE) ? Optional.of(Workbench.BLOCK) : Optional.empty());
        GrowthRules.register(Workbench.BLOCK, Workbench.RULE);
    }
}
