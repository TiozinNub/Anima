package dev.luizloyola.anima.compat.store;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.store.Store;
import java.util.Optional;

/**
 * Teaches perception what somewhere-to-put-things looks like — <b>by capability, not by a list</b>:
 * anything carrying an inventory is a store, so barrels and modded crates count without this file
 * ever learning their names.
 *
 * <p>Gated on {@code state.hasBlockEntity()} first, which is a flag read on the state; only then is
 * the block entity fetched. Perception probes a great many columns, and a block-entity lookup per
 * candidate is the kind of cost that does not show up until a settlement does.
 */
public final class StoreBlocks {

    private StoreBlocks() {
    }

    /** Call once from mod init. */
    public static void register() {
        BlockKinds.register((level, pos, state) -> {
            if (!state.hasBlockEntity()) {
                return Optional.empty();
            }
            return level.getBlockEntity(pos) instanceof net.minecraft.world.Container
                    ? Optional.of(Store.BLOCK) : Optional.empty();
        });
        GrowthRules.register(Store.BLOCK, Store.RULE);
    }
}
