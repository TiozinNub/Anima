package dev.luizloyola.anima.core.nav;

/**
 * How a walk weighs ground that could hurt it — set by whoever orders the walk, applied by
 * {@link Pathfinder}. Luiz, 2026-10-02: lava and big drops are refusals for a wander's spots and
 * routes, not only for where it stands, and a stroll stays on the surface.
 */
public enum Caution {
    /** The planner's own question, priced by time alone: tests, surveys, captures. */
    NONE,
    /**
     * A walk with somewhere to be. A cell beside harm, a leap over a gap that would hurt and ground
     * under the {@link Surface} are dear, never refused: the work may need them. Under the surface
     * costs nothing when the goal is down there.
     */
    ERRAND,
    /**
     * A wander, which needs nothing: no cell beside harm or beside a drop that would hurt, no leap
     * over one, and never further under the {@link Surface} than it set out.
     */
    STROLL;

    /** What a walk at this pace weighs when nobody said: a stroll is a wander, anything else has work. */
    public static Caution of(Gait gait) {
        return gait == Gait.STROLL ? STROLL : ERRAND;
    }
}
