package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.Coverage;
import dev.luizloyola.anima.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/** Any errand, plus a map to fill in while it runs. */
class SweepingErrandTest {

    private static final Coverage SINK = new Coverage() {
        @Override
        public void near(Pos here, int radius) {
        }

        @Override
        public void settled(Pos corner) {
        }
    };

    /** A stand-in primitive whose only trait worth naming is reshaping ground. */
    private static final class Digging implements PrimitiveTask {
        @Override
        public boolean reshapesGround() {
            return true;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            return TaskStatus.SUCCESS;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "dig";
        }
    }

    @Test
    void itDeclaresTheSinkItWasGiven() {
        assertSame(SINK, new SweepingErrand(new Idle(1), SINK).coverage());
    }

    @Test
    void itDecomposesToNothingButTheWork() {
        Task work = new Idle(1);
        SweepingErrand errand = new SweepingErrand(work, SINK);

        assertEquals(1, errand.methods().size(), "there is only ever one way to do the work");
        assertSame(work, errand.work());
    }

    @Test
    void itInheritsWhetherTheWorkReshapesGround() {
        assertTrue(new SweepingErrand(new Digging(), SINK).reshapesGround(),
                "a chop wrapped in a map is still a chop, and must not diagnose itself as stuck "
                        + "before the wrapper has expanded");
    }
}
