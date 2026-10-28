package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShutDoorTest {

    private static final Pos DOOR = new Pos(3, 64, 1);

    private final FakeContext ctx = new FakeContext();
    private final ShutDoor shut = new ShutDoor(DOOR.x(), DOOR.y(), DOOR.z());

    @Test
    void aClearDoorwayIsShutAtOnce() {
        assertEquals(TaskStatus.SUCCESS, shut.tick(ctx));
        assertEquals(List.of(DOOR), ctx.hand.shut);
    }

    @Test
    void itWaitsForSomebodyComingThroughThenShuts() {
        ctx.hand.crossing.add(DOOR);
        for (int i = 0; i < 20; i++) {
            assertEquals(TaskStatus.RUNNING, shut.tick(ctx));
        }
        assertTrue(ctx.hand.shut.isEmpty(), "not shut on them");

        ctx.hand.crossing.clear();
        assertEquals(TaskStatus.SUCCESS, shut.tick(ctx));
        assertEquals(List.of(DOOR), ctx.hand.shut);
    }

    @Test
    void aDoorwayKeptPastTheWaitFails() {
        ctx.hand.crossing.add(DOOR);
        for (int i = 0; i < ShutDoor.WAIT_TICKS; i++) {
            assertEquals(TaskStatus.RUNNING, shut.tick(ctx));
        }
        assertEquals(TaskStatus.FAILED, shut.tick(ctx));
        assertTrue(ctx.hand.shut.isEmpty());
    }

    @Test
    void aRestoredShutKeepsWhatItWaited() {
        ctx.hand.crossing.add(DOOR);
        ShutDoor restored = new ShutDoor(DOOR.x(), DOOR.y(), DOOR.z()).resume(ShutDoor.WAIT_TICKS);
        assertEquals(TaskStatus.FAILED, restored.tick(ctx));
    }
}
