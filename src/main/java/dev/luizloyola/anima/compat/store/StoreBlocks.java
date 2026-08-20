package dev.luizloyola.anima.compat.store;

import dev.luizloyola.anima.compat.sense.BlockKinds;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.store.Store;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>It is also the only thing in the mod that can see a double chest's joint, which is why it says
 * which HALF a cell is rather than only that it is a store: to a {@code BlockProbe} one double
 * chest and two singles side by side are the same two cells. The naming rule is
 * {@link Store#kindFor}.
 */
public final class StoreBlocks {

    private StoreBlocks() {
    }

    /** Call once from mod init. */
    public static void register() {
        BlockKinds.register((level, pos, state) -> {
            if (!state.hasBlockEntity()
                    || !(level.getBlockEntity(pos) instanceof net.minecraft.world.Container)) {
                return Optional.empty();
            }
            Direction joined = joinedHalf(state);
            return Optional.of(joined == null
                    ? Store.BLOCK
                    : Store.kindFor(joined.getStepX(), joined.getStepZ()));
        });
        // Every half is a seed: a far one glimpsed on its own has to start a growth too, or a
        // chest is remembered or not depending on which end of it a body walked past.
        Store.SEEDS.forEach(seed -> GrowthRules.register(seed, Store.RULE));
    }

    /**
     * Which way the other half of a double chest lies, or null for anything joined to nothing.
     * Read off the state alone — no neighbour lookup, so telling the halves apart adds nothing to
     * the block-entity fetch already paid above.
     */
    private static @Nullable Direction joinedHalf(BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return ChestBlock.getConnectedDirection(state);
    }
}
