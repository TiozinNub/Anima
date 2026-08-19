package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;

/**
 * The container actuator port — reaching into somewhere that holds things.
 *
 * <p><b>Reach-gated like the arm</b>: every verb refuses a cell the body cannot touch, so a settler
 * cannot rifle a chest from across the room. An out-of-reach or non-container cell answers empty
 * rather than throwing — a belief can be wrong about what is there, and that is a normal Tuesday.
 */
public interface ContainerAccess {

    /**
     * What is in the container at {@code at}, or empty when there is no container there or it is out
     * of reach. A present-but-empty container answers an empty LIST, which is a different fact.
     */
    Optional<List<ItemStack>> contents(Pos at);

    /** Puts what fits; returns how many items were accepted, 0 when the container is full. */
    int insert(Pos at, ItemStack stack);

    /** Removes up to {@code max} matching items; returns what came out, {@code EMPTY} if none did. */
    ItemStack take(Pos at, ItemSpec spec, int max);
}
