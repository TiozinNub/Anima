package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shape every timed action shares: idle until started, running while it counts down, and true
 * exactly once — on the tick the work completes.
 */
class PauseTest {

    private final Pause pause = new Pause();

    @Test
    void aFreshPauseIsIdleAndElapsesNothing() {
        assertTrue(pause.idle());
        assertFalse(pause.elapsed(), "an unstarted pause never completes");
        assertEquals(0, pause.remaining());
    }

    @Test
    void itCompletesOnTheLastTickAndNotBefore() {
        pause.start(3);
        assertFalse(pause.idle());
        assertFalse(pause.elapsed());
        assertFalse(pause.elapsed());
        assertTrue(pause.elapsed(), "the third step is the one that finishes it");
        assertTrue(pause.idle(), "and it is idle again, ready for the next unit");
    }

    @Test
    void itCompletesOnlyOnce() {
        pause.start(1);
        assertTrue(pause.elapsed());
        assertFalse(pause.elapsed(), "a completed pause must not fire again for a later phase");
    }

    @Test
    void aZeroLengthPauseIsSimplyIdle() {
        pause.start(0);
        // Idle again immediately — documents the clamp, not a safe way to skip a phase. A caller
        // that starts the next unit on idle would spin on this forever; duration knobs are floored
        // at 1 so they never hand this a zero.
        assertTrue(pause.idle());
        assertFalse(pause.elapsed());
    }

    @Test
    void aNegativePauseIsClampedRatherThanNeverElapsing() {
        pause.start(-5);
        assertTrue(pause.idle());
        assertEquals(0, pause.remaining());
    }

    @Test
    void restorePutsAReloadBackMidPause() {
        pause.restore(2);
        assertEquals(2, pause.remaining());
        assertFalse(pause.elapsed());
        assertTrue(pause.elapsed(), "a reload resumes the pause rather than restarting the work");
    }
}
