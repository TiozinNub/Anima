package dev.luizloyola.anima.mod.body;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.LeanState;
import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.mod.nav.Navigator;
import org.junit.jupiter.api.Test;

/** When a look may turn the body, not only the head: {@link Gaze#inControl}. */
class GazeTest {

    @Test
    void aBodyWithAnOrderKeepsItsFacing() {
        // Following, and between paths too: a re-plan mid-walk is still a walk.
        for (Navigator.State legs : new Navigator.State[] {
                Navigator.State.PATHING, Navigator.State.FOLLOWING}) {
            assertTrue(Gaze.inControl(legs, RiseState.IDLE, LeanState.IDLE), legs.name());
        }
    }

    @Test
    void aRiseOrALeanKeepsItsFacing() {
        assertTrue(Gaze.inControl(Navigator.State.IDLE, RiseState.RISING, LeanState.IDLE));
        for (LeanState lean : new LeanState[] {
                LeanState.LEANING, LeanState.LEANT, LeanState.RELEASING}) {
            assertTrue(Gaze.inControl(Navigator.State.IDLE, RiseState.IDLE, lean), lean.name());
        }
    }

    @Test
    void aBodyNothingHoldsTurnsToLook() {
        for (Navigator.State legs : new Navigator.State[] {
                Navigator.State.IDLE, Navigator.State.ARRIVED, Navigator.State.FAILED}) {
            assertFalse(Gaze.inControl(legs, RiseState.IDLE, LeanState.IDLE), legs.name());
        }
        assertFalse(Gaze.inControl(Navigator.State.IDLE, RiseState.RISEN, LeanState.FAILED),
                "a finished rise and a failed lean hold nothing");
    }
}
