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
 * <p><b>This classifier does what {@link BlockKinds} tells classifiers never to do</b> — it fetches
 * a block entity. Capability is where the inventory lives, so there is no other way to recognise a
 * container without the name list this exists to avoid. {@code state.hasBlockEntity()} is a flag on
 * the state, so gating on it first means the fetch is paid only for the few cells that could
 * possibly have one; that bounds the cost, it does not make the read free.
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
