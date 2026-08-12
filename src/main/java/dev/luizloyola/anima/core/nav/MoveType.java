package dev.luizloyola.anima.core.nav;

/**
 * How a waypoint is entered from the one before it, and so how the follower drives it:
 * {@link #WALK} (cardinal, diagonal or stride) is plain forward input, {@link #JUMP} presses jump,
 * {@link #DROP} walks off the edge, {@link #LEAP} presses at the edge with a sprint run-up for gaps
 * 2+ wide, {@link #SWIM} ends with the feet in water and holds jump for buoyancy. Climbing out is
 * an ordinary {@link #WALK}/{@link #JUMP} onto solid ground. {@link #DIVE} and {@link #SURFACE} are
 * the vertical pair — they may slope, but their point is depth, and they alone are driven AGAINST
 * buoyancy.
 */
public enum MoveType {
    WALK,
    JUMP,
    DROP,
    LEAP,
    SWIM,
    /** Down inside water: the head goes under, or further under. */
    DIVE,
    SURFACE;

    /**
     * Whether this move ends with the body <em>in</em> the water rather than on its feet. The three
     * share every downstream rule — steering, what keeps a waypoint walkable, whether a body may
     * claim to have reached it — so ask the move; listing them at each site is how the next water
     * move gets added to only two.
     *
     * <p>Climbing OUT is not one: its destination is solid ground, an ordinary
     * {@link #WALK}/{@link #JUMP} that starts wet.
     */
    public boolean inWater() {
        return this == SWIM || this == DIVE || this == SURFACE;
    }
}
