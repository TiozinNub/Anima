package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * The place actuator port — the working arm's other verb. Placing is instantaneous in vanilla, so
 * this port is a one-shot rather than a begin/state/abort lifecycle: one call either places and
 * returns {@code true} (block set for real, place sound, arm swing, one item of {@code itemId}
 * consumed from the CARRIED INVENTORY — the source of truth, the equipment mirror follows) or
 * refuses and returns {@code false} — nothing carried, target occupied, the block can't survive
 * there, out of reach — with nothing changed.
 *
 * <p>A cell already holding the same block takes it again where vanilla counts it — a second
 * candle, a slab made double — and a door or a bed places its other half, as they do for a player.
 */
public interface BlockPlacer {
    /** True exactly when the world changed. */
    boolean place(Placing placing);

    default boolean place(String itemId, Pos target) {
        return place(Placing.of(itemId, target));
    }
}
