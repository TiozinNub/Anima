package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.BeingId;

/**
 * The fighting arm: whether a blow at somebody could land, how charged the next one is, and the
 * blow itself. The mod side is vanilla's melee attack, done the way a player's is — the same
 * damage, the same charge, the same sweep.
 *
 * <p>The target is a {@link BeingId}, never an entity: a task knows whom it fights through its
 * percepts, and the arm answers for the body it actually finds there, as a server checks a
 * player's attack against the entity it names.
 */
public interface Striker {

    /** Where a blow at a target stands right now. */
    enum Reach {
        /** Close enough, in sight and alive: a blow would land. */
        IN_REACH,
        /** Alive, but beyond arm's length. */
        OUT_OF_REACH,
        /** Within arm's length, but something is in the way. */
        BLOCKED,
        /** Dying or dead. */
        DEAD,
        /** Not in this world: despawned, unloaded, or somewhere else. */
        GONE
    }

    Reach reach(BeingId target);

    /**
     * How far the next swing has charged, 0 to 1 — a player's attack strength. Every swing of the
     * arm and every change of what the hand holds starts it again, mining included.
     */
    double charge();

    /** Swings at {@code target}; true when the blow landed. Ask {@link #reach} first. */
    boolean strike(BeingId target);

    /** A body with no fighting arm: nothing is ever in reach. */
    Striker NONE = new Striker() {
        @Override
        public Reach reach(BeingId target) {
            return Reach.GONE;
        }

        @Override
        public double charge() {
            return 0.0;
        }

        @Override
        public boolean strike(BeingId target) {
            return false;
        }
    };
}
