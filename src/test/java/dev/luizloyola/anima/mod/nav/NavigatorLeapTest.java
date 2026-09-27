package dev.luizloyola.anima.mod.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When a grounded body presses a leap: {@link Navigator#pressLeap}. Distances are from the body to
 * the landing centre, velocities in blocks a tick, as the follower reads them.
 */
class NavigatorLeapTest {

    /** A 2-block gap, gauntlet A12's. */
    private static final double SPAN = 3.0;

    @Test
    void aStraightChainPressesTheTickItLands() {
        assertTrue(Navigator.pressLeap(2.7, 0.0, 0.16, 0.0, SPAN),
                "landed on the far rim of the last leap, already moving at this gap");
    }

    @Test
    void aTurnedChainTurnsBeforeItPresses() {
        // A12's runner, 2026-09-26: landed on (606,137) from the leap south, the next leap east.
        assertFalse(Navigator.pressLeap(2.69, 0.25, -0.03, 0.134, SPAN),
                "still carried south across the gap");
        assertTrue(Navigator.pressLeap(2.55, 0.18, 0.14, 0.07, SPAN), "a tick later, turned");
    }

    @Test
    void aTurnedChainPressesWhenItsFootingRunsOut() {
        assertTrue(Navigator.pressLeap(2.35, 0.2, -0.03, 0.134, SPAN));
    }

    @Test
    void aStandingBodyPresses() {
        assertTrue(Navigator.pressLeap(2.7, 0.0, 0.0, 0.0, SPAN));
    }

    @Test
    void neverOutsideTheTakeoffWindow() {
        assertFalse(Navigator.pressLeap(2.9, 0.0, 0.2, 0.0, SPAN), "a step short of the rim");
        assertFalse(Navigator.pressLeap(1.7, 0.0, 0.2, 0.0, SPAN), "over the gap, or landed");
    }
}
