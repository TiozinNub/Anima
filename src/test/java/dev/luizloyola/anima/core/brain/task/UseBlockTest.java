package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One use of the empty hand: a handling beat, then the world changed or it did not. */
class UseBlockTest {

    private static final Pos BUSH = new Pos(3, 64, 0);

    private final FakeContext ctx = new FakeContext();

    private int ticksTo(UseBlock use, TaskStatus end) {
        for (int tick = 1; tick <= 200; tick++) {
            TaskStatus status = use.tick(ctx);
            if (status != TaskStatus.RUNNING) {
                assertEquals(end, status);
                return tick;
            }
        }
        throw new AssertionError("still RUNNING after 200 ticks");
    }

    @Test
    void aUseThatChangesTheWorldSucceedsAfterOneHandlingBeat() {
        ctx.hand.usable.add(BUSH);

        int ticks = ticksTo(new UseBlock(BUSH.x(), BUSH.y(), BUSH.z()), TaskStatus.SUCCESS);

        assertEquals(ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS), ticks);
        assertEquals(List.of(BUSH), ctx.hand.used, "one use, on the tick the beat ends");
    }

    @Test
    void aUseThatChangesNothingFails() {
        ticksTo(new UseBlock(BUSH.x(), BUSH.y(), BUSH.z()), TaskStatus.FAILED);
        assertEquals(List.of(BUSH), ctx.hand.used, "picked over already, or out of reach");
    }

    @Test
    void aReloadMidBeatWaitsOnlyWhatWasLeft() {
        ctx.hand.usable.add(BUSH);
        UseBlock use = new UseBlock(BUSH.x(), BUSH.y(), BUSH.z()).resume(2);

        assertEquals(2, ticksTo(use, TaskStatus.SUCCESS));
        assertTrue(ctx.hand.usable.isEmpty());
    }
}
