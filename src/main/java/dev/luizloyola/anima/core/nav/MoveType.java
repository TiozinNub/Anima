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
    SURFACE,
    /**
     * On a ladder or vines: up or down the column, in from the side onto a hold, or off the top
     * onto a ledge. Held up by the climbable rather than by a floor, so the follower holds jump to
     * go up and lets go to come down, and never presses jump off the ground for it.
     */
    CLIMB,
    /**
     * A level cardinal step onto a block laid first in the gap under the destination: a deck, laid
     * against the floor of the cell before it. Offered only to a search allowed to build
     * ({@link MoveCapabilities#maxLaid}).
     */
    BRIDGE,
    /** Up one in place: jump, lay a block in the cell just left, land on it — the riser's move. */
    PILLAR,
    /**
     * Up two or three onto a step of soft ground: the top block (or two) of the step broken, a
     * jump into the notch, and the same blocks laid back underfoot. The world ends as it began, so
     * nothing is spent and nothing is left — but the floor the waypoint stands on is one the leg
     * put back ({@link #rebuildsFloor()}).
     */
    SCALE,
    /**
     * Down one, by breaking the recorded pillar block underfoot and dropping with it — the way a
     * pillar is cleaned up, by whoever goes down it. The cut is the waypoint's own feet cell.
     */
    LOWER,
    /**
     * Up one into a two-block step whose lip was broken first and kept: the step is one block for
     * good. Only where the notch would look natural, so nothing records it. The cut is the
     * waypoint's own feet cell.
     */
    CARVE;

    /**
     * Whether entering this waypoint lays a block — always into the cell directly under it, which
     * is how the follower and {@link PathIntegrity} find it without the waypoint saying so.
     */
    public boolean lays() {
        return this == BRIDGE || this == PILLAR;
    }

    /**
     * Whether the floor under this waypoint is one the leg itself puts there — laid, or cut and
     * put back. Such a floor is missing while the leg runs, and whatever looks ahead must not read
     * that as the route breaking.
     */
    public boolean rebuildsFloor() {
        return lays() || this == SCALE;
    }

    /**
     * Whether entering this waypoint breaks the block in its own feet cell. The grid still holds
     * that block, so nothing that reads the cell may take it for the ground it was.
     */
    public boolean cutsItsCell() {
        return this == LOWER || this == CARVE;
    }

    /** Whether entering this waypoint needs the hand: something laid, cut, or both. */
    public boolean worked() {
        return rebuildsFloor() || cutsItsCell();
    }

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
