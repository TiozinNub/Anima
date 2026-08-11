package dev.luizloyola.anima.core.nav;

/**
 * How a waypoint is entered from the one before it, and so how the follower drives it:
 * {@link #WALK} (cardinal, diagonal or stride) is plain forward input, {@link #JUMP} presses jump,
 * {@link #DROP} walks off the edge, {@link #LEAP} presses at the edge with a sprint run-up for gaps
 * 2+ wide, {@link #SWIM} ends with the feet in water and holds jump for buoyancy. Climbing out is
 * an ordinary {@link #WALK}/{@link #JUMP} onto solid ground. {@link #DIVE} and {@link #SURFACE}
 * move within a water column — no horizontal ground, and the only moves driven AGAINST buoyancy.
 */
public enum MoveType {
    WALK,
    JUMP,
    DROP,
    LEAP,
    SWIM,
    /** Down one cell inside water: the head goes under, or further under. */
    DIVE,
    /** Up one cell inside water, toward the air. */
    SURFACE
}
