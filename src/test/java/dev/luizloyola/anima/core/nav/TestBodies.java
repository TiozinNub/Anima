package dev.luizloyola.anima.core.nav;

/**
 * The body the navigation tests are written against — Anima ships no such constant any more,
 * since capabilities are a species' declaration. Every expected path in {@code PathfinderTest}
 * and every rule in {@code PathIntegrityTest} was derived by hand for this one, so it lives here
 * rather than in production code where it would quietly become somebody's default.
 */
final class TestBodies {

    /**
     * Two cells tall (a 1.8 hitbox), jumps 1, drops 3, leaps 3 (the vanilla sprint limit), swims.
     *
     * <p>36 cells of submerged travel: a 300-tick lungful at the planner's five ticks a cell with
     * its reserve kept back — a real body's number, not a round one.
     */
    static final MoveCapabilities BIPED = new MoveCapabilities(1.8, 1, 3, 3, true, 36);

    private TestBodies() {
    }
}
