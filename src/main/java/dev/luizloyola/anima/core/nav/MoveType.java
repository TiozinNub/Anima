package dev.luizloyola.anima.core.nav;

/**
 * How a waypoint is entered from the one before, and so what the follower presses: {@link #JUMP}
 * the jump input; {@link #DROP} the edge and gravity; {@link #WALK} (cardinal, diagonal or stride —
 * one thing to the driver) plain forward input; {@link #LEAP} a jump across a gap, pressed at the
 * edge, off the end of a {@link #RUNUP} when the gap is 2+ wide; {@link #SWIM} forward with jump
 * held for buoyancy, ending with the feet in a water cell. Climbing back out is an ordinary
 * {@link #WALK}/{@link #JUMP} onto solid ground, known as the exit because the body is still in the
 * water. {@link #DIVE} and {@link #SURFACE} are the vertical pair — they may slope, but the point
 * is the change of depth, and they alone are driven AGAINST buoyancy.
 */
public enum MoveType {
    WALK,
    JUMP,
    DROP,
    LEAP,
    /**
     * The accelerating step onto the takeoff cell of a {@link #LEAP} — the second half of a jump
     * that is really two moves. A body standing ON the takeoff has half a block of runway, a
     * standing jump however hard sprint is pressed: a 2-cell gap falls short, a 3-cell gap is not
     * close. Where the route has no such step, the search goes back a cell to make one.
     *
     * <p>Geometrically whatever that step was — walk, diagonal, stride, or a jump when the takeoff
     * is a block up (a staircase summit), read off the rise as for a {@link #JUMP}. Its own move
     * because of what the follower must not do on it: no careful throttle (the takeoff borders the
     * gap by definition), no landing brake, no stroll, no crowd swerve, and no claiming it by
     * standing in it.
     *
     * <p>Never the last waypoint of a path.
     */
    RUNUP,
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
