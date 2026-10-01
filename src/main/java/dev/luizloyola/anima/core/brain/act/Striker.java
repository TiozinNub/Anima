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

    /** Where a blow at {@code target} stands, at full reach. */
    default Reach reach(BeingId target) {
        return reach(target, 0.0);
    }

    /**
     * Where a blow at {@code target} stands, counting it in reach only within {@code inset} blocks
     * short of full reach — a fighter steps in rather than swinging from the very edge.
     */
    Reach reach(BeingId target, double inset);

    /**
     * How far the next swing has charged, 0 to 1 — a player's attack strength. Every swing of the
     * arm and every change of what the hand holds starts it again, mining included.
     */
    double charge();

    /** Swings at {@code target}; true when the blow landed. Ask {@link #reach} first. */
    boolean strike(BeingId target);

    /**
     * Asks for the pack's best weapon against {@code target} in hand ({@link WeaponChoice}); true
     * while the hand is changing, and on the tick it changed. The change is a timed item move, so
     * ask every tick while {@link #changing}. A hand that just changed has not charged, whatever
     * {@link #charge} said a moment ago.
     */
    boolean draw(BeingId target);

    /**
     * Ticks until a blow at {@code target} would land: 0 when it would land now. A body just hurt
     * shuts out every blow for 10 ticks unless it is bigger than the one that hurt it, and a blow
     * shut out still spends the swing's charge — so a fist that charges faster than that wastes
     * every other blow.
     */
    default int shutOutTicks(BeingId target) {
        return 0;
    }

    /** Whether the hands are in the middle of changing what they hold. */
    default boolean changing() {
        return false;
    }

    /** A body with no fighting arm: nothing is ever in reach. */
    Striker NONE = new Striker() {
        @Override
        public Reach reach(BeingId target, double inset) {
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

        @Override
        public boolean draw(BeingId target) {
            return false;
        }
    };
}
