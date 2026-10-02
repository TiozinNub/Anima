package dev.luizloyola.anima.mod.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The climb-out's lift, {@link Swimmer#liftsOut}. A wade from one cell of a one-deep pool to the
 * next is an exit at the bed's own height, and lifting it floated the body off the bed: the cell
 * before a run-up is reached on the feet, so it never was (forest, 2026-10-02).
 */
class SwimmerLiftTest {

    @Test
    void aWaderStepsOnAlongTheBedUnlifted() {
        assertFalse(Swimmer.liftsOut(55.0, 55.0));
    }

    @Test
    void aBodyBelowTheBankIsLiftedOntoIt() {
        assertTrue(Swimmer.liftsOut(55.0, 56.0), "a one-deep pool's bank, a block up");
        assertTrue(Swimmer.liftsOut(54.6, 55.0),
                "treading deep water beside a beach at the surface");
    }
}
