package dev.luizloyola.anima.core.nav;

/**
 * What a walk may do to the ground it crosses (docs/superpowers/specs/2026-09-28-bridging-design.md).
 * Whoever orders the walk sets it; the body's own hands, and what it carries, decide the rest.
 */
public enum WalkLevel {
    /** Nothing but walking: wander, flee, fight, following, company. */
    WALK_ONLY,
    /**
     * Also scale a soft step two or three high, putting its lip back — needs nothing in the
     * pocket and leaves nothing changed, so it is every purposeful walk's default.
     */
    SCALE,
    /** Also bridge a gap and pillar up with the blocks it carries: a walk that asked to build. */
    BUILD;

    public boolean scales() {
        return this != WALK_ONLY;
    }

    public boolean builds() {
        return this == BUILD;
    }

    /**
     * What a walk at this pace may do when nobody said: a stroll and a sprint are a wander and a
     * flight, and neither stops to move the ground; a plain walk is going somewhere.
     */
    public static WalkLevel of(Gait gait) {
        return gait == Gait.WALK ? SCALE : WALK_ONLY;
    }
}
