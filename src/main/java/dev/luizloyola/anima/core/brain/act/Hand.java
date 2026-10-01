package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * The empty hand used on a block — what a player's right-click does with nothing held. A one-shot,
 * like {@link BlockPlacer}. Anima knows no block this does anything to: whoever knows one registers
 * what using it means (a consumer's berry bush), and every other block is left alone.
 */
public interface Hand {

    /** A body with no hand to use: nothing ever changes. */
    Hand NONE = target -> false;

    /** Use the block at the cell; true exactly when the world changed. Out of reach changes nothing. */
    boolean use(Pos target);

    /**
     * Use the block at the cell with a carried {@code itemId} in hand — a player's click holding it.
     * True exactly when the world changed; not carried, or out of reach, changes nothing.
     */
    default boolean use(String itemId, Pos target) {
        return false;
    }

    /**
     * Shut the door, gate or hatch whose lowest cell is {@code door}: by hand, or by the lever that
     * holds an iron door open. True when it stands shut afterwards, whether it was already; false
     * out of reach, with no door there, or with one this body cannot shut.
     */
    default boolean shut(Pos door) {
        return false;
    }
}
