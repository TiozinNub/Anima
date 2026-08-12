package dev.luizloyola.anima.core.brain.act;

/**
 * Why a move order ended badly — the question {@link MoveState#FAILED} cannot answer on its own.
 * The legs already observe all of it (four sites in the mod Navigator); it had nowhere to
 * go, so nothing above the {@link Mover} could tell "I am trapped" from "that errand is off".
 *
 * <p><b>One value per site, never a judgement.</b> Each constant names what the legs observed, not
 * what it implies about the world: {@link #STRANDED} says the search offered no way out of where we
 * stand, which is evidence of being sealed in but not the same claim. Proving confinement is a
 * separate, stronger fact (the search's own reachable region) and belongs to whoever asks for it.
 *
 * <p><b>Names are persisted</b> (the saved walk carries this by name), so renaming a constant
 * orphans saved walks while adding one is free. Append; don't rename.
 */
public enum MoveFailure {
    /** No failure — what every mover reports when its last order did not end badly. */
    NONE(""),

    /**
     * The search offered no way out of where we stand: no route at all, or a partial one ending
     * where the body already is. Sealed in a box, shut in a room, marooned on a one-block pillar —
     * the legs cannot tell those apart and do not try to.
     */
    STRANDED("stranded"),
    /**
     * The route kept running out short of the goal until the retries ran out. The world around the
     * body is fine; that <em>destination</em> is not currently walkable to. A task hearing this
     * should give up the goal, never the world.
     */
    UNREACHABLE("unreachable"),
    /**
     * Found off the plan while grounded — shoved, dropped through a broken floor, a botched jump.
     * The plan was sound; the body is no longer on it.
     */
    STRAYED("strayed off the path"),
    /**
     * On the plan and moving, but not arriving: one cell took impossibly long. Circling, sliding,
     * pushed back as fast as it walks, fighting a current.
     */
    STALLED("stalled"),
    /**
     * Driven, and not moving at all. Wedged against something the snapshot did not know about — a
     * fence lip, a corner, another body.
     */
    WEDGED("wedged"),
    /** The search itself died: a worker gone, or the server stopping. Transient; nothing is wrong
     *  with the body or the terrain. */
    INTERRUPTED("search interrupted"),
    /**
     * The legs were taken by somebody else mid-order. The one value the legs never report — a
     * stopped Navigator is IDLE, not FAILED — so it is the reading a task makes of a mover that
     * has gone idle under an order it never finished.
     */
    STOPPED("legs taken");

    private final String description;

    MoveFailure(String description) {
        this.description = description;
    }

    /**
     * The short phrase for a journal line or a readout — {@code "wedged"}, {@code "strayed off the
     * path"}. Empty for {@link #NONE}, so a caller appending it never prints a dangling dash.
     */
    public String describe() {
        return this.description;
    }
}
