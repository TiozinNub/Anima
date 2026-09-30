package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link Scout}: the herd's ground and a ring around it, a look at each, and forgetting a herd that is gone. */
class ScoutTest {

    private final FakeContext ctx = new FakeContext();
    private final Pos anchor = new Pos(30, 64, 0);

    @BeforeEach
    void setUp() {
        ctx.mover.setState(MoveState.ARRIVED);
        ctx.knowledge.note(new PoiMemory(PoiKind.HERD, "cow", anchor,
                new Region(new Pos(26, 62, -4), new Pos(34, 66, 4)), 5, false, 0L), 64);
    }

    private TaskStatus run(Scout scout, int limit) {
        for (int tick = 0; tick < limit; tick++) {
            TaskStatus status = scout.tick(ctx);
            if (status != TaskStatus.RUNNING) {
                return status;
            }
        }
        return TaskStatus.RUNNING;
    }

    @Test
    void itWalksTheHerdsGroundThenTheRingLookingAtEach() {
        Scout scout = new Scout("cow", anchor, 10);

        assertEquals(TaskStatus.FAILED, run(scout, 1000));
        assertEquals(List.of("moveTo(30, 64, 0)", "moveTo(40, 64, 0)", "moveTo(30, 64, 10)",
                "moveTo(20, 64, 0)", "moveTo(30, 64, -10)"), ctx.mover.events.stream()
                .filter(event -> event.startsWith("moveTo")).toList());
    }

    @Test
    void aHerdThatIsNotThereIsForgotten() {
        Scout scout = new Scout("cow", anchor, 10);

        assertEquals(TaskStatus.FAILED, run(scout, 1000));
        assertTrue(ctx.knowledge.all(PoiKind.HERD).isEmpty(), "a herd looked for and not found is gone");
        assertEquals("no cow at 30, 64, 0 any more", scout.failureDetail());
    }

    @Test
    void aCowInSightEndsIt() {
        Scout scout = new Scout("cow", anchor, 10);
        run(scout, 30); // at the herd's ground, looking round
        ctx.percepts.beings = List.of(FakePercepts.animalAt(BeingId.of(UUID.randomUUID()), "cow",
                new Pos(41, 64, 3), 11.0));

        assertEquals(TaskStatus.SUCCESS, scout.tick(ctx));
        assertEquals(1, ctx.knowledge.all(PoiKind.HERD).size(), "found, so remembered still");
    }

    @Test
    void aStopWithNoFootingIsPassedOver() {
        ctx.percepts.terrain = dev.luizloyola.anima.core.nav.NavGrid.UNKNOWN;
        Scout scout = new Scout("cow", anchor, 10);

        assertEquals(TaskStatus.FAILED, run(scout, 10));
        assertEquals(0, ctx.mover.moveToCalls);
    }
}
