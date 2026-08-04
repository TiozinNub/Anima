package dev.luizloyola.anima.compat.sense;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a real block is sorted into a {@link BlockKind}, and how a consuming mod teaches perception
 * to recognise a kind of its own: Anima's own answer is the short ladder in {@link LevelProbe#at},
 * and a mod that perceives a distinction vanilla has no shape for registers a classifier here
 * beside its {@code BlockKind}.
 *
 * <p><b>Consumers are asked first, in reverse registration order</b>, with Anima's ladder as the
 * floor beneath them — same rule as {@code BeingKinds}. That ordering is what makes a whole class
 * of block perceivable at all, since the floor's second-to-last rung turns every collision-free
 * cell into {@link BlockKind#AIR} and the walk-through plants are collision-free too.
 *
 * <p><b>Air is settled before anyone is asked</b> — far and away the commonest read. Nothing can
 * reclassify literal air.
 *
 * <p>Classifiers are on the hot path (tens of block reads per body per tick), so keep one to tag
 * lookups and property reads, and never touch a block entity or a chunk this cell did not already
 * need.
 */
public final class BlockKinds {

    /** Consumer classifiers, newest first. Copy-on-write: registered at init, read per block. */
    private static final List<Classifier> REGISTERED = new CopyOnWriteArrayList<>();

    private BlockKinds() {
    }

    /** Recognises blocks a general rule would misclassify. Return empty to defer to the floor. */
    @FunctionalInterface
    public interface Classifier {
        /**
         * @param level where the block is, for a shape or a property that needs its neighbours —
         *              already loaded, since the probe checked before reading the state
         * @param pos   the cell, valid only for the duration of the call
         * @param state what stands there; never air
         */
        Optional<BlockKind> classify(BlockGetter level, BlockPos pos, BlockState state);
    }

    /**
     * Teaches perception to recognise a kind. Call during mod initialization; the most recently
     * registered classifier is asked first.
     */
    public static void register(Classifier classifier) {
        REGISTERED.add(0, classifier);
    }

    /**
     * What this block is to a consumer, or empty for "nobody claimed it — use the floor".
     *
     * <p>Indexed rather than for-each on purpose: this runs per block read, and a
     * {@link CopyOnWriteArrayList} iterator is an allocation each time where an index is not.
     */
    public static Optional<BlockKind> of(BlockGetter level, BlockPos pos, BlockState state) {
        for (int i = 0; i < REGISTERED.size(); i++) {
            Optional<BlockKind> said = REGISTERED.get(i).classify(level, pos, state);
            if (said.isPresent()) {
                return said;
            }
        }
        return Optional.empty();
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
