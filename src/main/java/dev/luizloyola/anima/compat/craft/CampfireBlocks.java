package dev.luizloyola.anima.compat.craft;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.craft.Campfire;
import java.util.Optional;
import net.minecraft.tags.BlockTags;

/** Teaches perception what a campfire looks like — the classifier half of {@link Campfire}. */
public final class CampfireBlocks {

    private CampfireBlocks() {
    }

    /** Call once from mod init. By the vanilla tag: a soul campfire cooks as well. */
    public static void register() {
        BlockKinds.register((level, pos, state) ->
                state.is(BlockTags.CAMPFIRES) ? Optional.of(Campfire.BLOCK) : Optional.empty());
        GrowthRules.register(Campfire.BLOCK, Campfire.RULE);
    }
}
