package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Yields;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link SeekPrey}: legs on a heading, a look at each end, and a rest when nothing turned up. */
class SeekPreyTest {

    private final FakeContext ctx = new FakeContext();

    @BeforeEach
    void setUp() {
        HuntTest.installYields();
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ReadyFood.install(ctx.percepts.foods());
        ctx.mover.setState(MoveState.ARRIVED);
    }

    @AfterEach
    void uninstall() {
        Yields.install(null);
        ReadyFood.install(null);
    }

    private TaskStatus run(SeekPrey seek, int limit) {
        for (int tick = 0; tick < limit; tick++) {
            TaskStatus status = seek.tick(ctx);
            if (status != TaskStatus.RUNNING) {
                return status;
            }
        }
        return TaskStatus.RUNNING;
    }

    @Test
    void itWalksFourLegsLooksAtEachAndRestsTheGround() {
        SeekPrey seek = new SeekPrey(ReadyFood.SPEC);

        assertEquals(TaskStatus.FAILED, run(seek, 1000));
        assertEquals(SeekPrey.LEGS, ctx.mover.moveToCalls);
        assertEquals("found nothing to hunt", seek.failureDetail());
        assertTrue(ctx.knowledge.isAvoided(PoiKind.HERD, SeekPrey.restKey(ctx.percepts.position()),
                ctx.percepts.time), "hunger asking again does not send it straight back out");
        assertFalse(new Hunt(ReadyFood.SPEC, null).applicable(ctx));
    }

    @Test
    void eachLegIsALegLongOnTheHeading() {
        SeekPrey seek = new SeekPrey(ReadyFood.SPEC);
        seek.tick(ctx);
        double angle = seek.heading() * Math.PI / 4.0;

        assertEquals(Math.round(SeekPrey.LEG * Math.cos(angle)), ctx.mover.lastX);
        assertEquals(Math.round(SeekPrey.LEG * Math.sin(angle)), ctx.mover.lastZ);
    }

    @Test
    void aCowInSightEndsIt() {
        SeekPrey seek = new SeekPrey(ReadyFood.SPEC);
        run(seek, 5);
        ctx.percepts.beings = List.of(FakePercepts.animalAt(BeingId.of(UUID.randomUUID()), "cow",
                new Pos(40, 64, 3), 12.0));

        assertEquals(TaskStatus.SUCCESS, seek.tick(ctx));
    }

    @Test
    void aLegThatCannotBeWalkedTurnsAQuarter() {
        SeekPrey seek = new SeekPrey(ReadyFood.SPEC);
        ctx.mover.setState(MoveState.FAILED);
        seek.tick(ctx);
        int first = seek.heading();
        seek.tick(ctx);

        assertEquals((first + 2) % 8, seek.heading());
    }
}
