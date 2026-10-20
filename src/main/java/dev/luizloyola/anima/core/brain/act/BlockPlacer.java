package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Optional;

/**
 * The place actuator port — the working arm's other verb. Placing is instantaneous in vanilla, so
 * this port is a one-shot rather than a begin/state/abort lifecycle: one call either places (block
 * set for real, place sound, arm swing, one item of {@code itemId} consumed from the CARRIED
 * INVENTORY — the source of truth, the equipment mirror follows) or refuses with nothing changed,
 * and says why ({@link Refusal}).
 *
 * <p>A cell already holding the same block takes it again where vanilla counts it — a second
 * candle, a slab made double — and a door or a bed places its other half, as they do for a player.
 */
public interface BlockPlacer {

    /** Why the placer said no. Only {@link #OUT_OF_REACH} is cured by standing elsewhere. */
    enum Refusal {
        NOT_CARRIED("none carried"),
        NOTHING_AGAINST("nothing beside it to place against"),
        OUT_OF_REACH("out of reach"),
        OCCUPIED("the cell is taken"),
        CANNOT_SURVIVE("it cannot stand there"),
        /** Not a block, one survival cannot place, or a state no hand makes. */
        NOT_PLACEABLE("no hand places it so");

        private final String why;

        Refusal(String why) {
            this.why = why;
        }

        /** The journal's words for it. */
        public String why() {
            return why;
        }
    }

    /** Empty exactly when the world changed. */
    Optional<Refusal> attempt(Placing placing);

    /** True exactly when the world changed. */
    default boolean place(Placing placing) {
        return attempt(placing).isEmpty();
    }

    default boolean place(String itemId, Pos target) {
        return place(Placing.of(itemId, target));
    }
}
