package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Waypoint;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A flight leg that will not run into a pit ({@link Path#trapped}, 2026-09-30). */
class RunAwayTest {

    private static final Pos PIT = new Pos(12, 61, 0);
    private static final Pos FIELD = new Pos(0, 64, 12);
    private static final Pos OTHER = new Pos(-12, 64, 0);

    private final FakeContext ctx = new FakeContext();

    @Test
    void aRouteIntoAPitIsDroppedForTheNextTarget() {
        RunAway leg = new RunAway(List.of(PIT, FIELD));
        assertEquals(TaskStatus.RUNNING, leg.tick(ctx));
        assertEquals(PIT.x(), ctx.mover.lastX);
        ctx.mover.setState(MoveState.MOVING);
        ctx.mover.route = route(PIT).trap();
        assertEquals(TaskStatus.RUNNING, leg.tick(ctx), "still running: another target is left");
        assertEquals(1, ctx.mover.stopCalls);
        assertEquals(2, ctx.mover.moveToCalls, "the next target ordered in the same tick");
        assertEquals(FIELD.z(), ctx.mover.lastZ);
        assertEquals("goto (0, 64, 12) (sprint)", leg.describe());

        ctx.mover.setState(MoveState.MOVING); // the fake stays stopped until told
        ctx.mover.route = null; // the new search is out
        assertEquals(TaskStatus.RUNNING, leg.tick(ctx));
        ctx.mover.route = route(FIELD);
        assertEquals(TaskStatus.RUNNING, leg.tick(ctx));
        ctx.mover.setState(MoveState.ARRIVED);
        assertEquals(TaskStatus.SUCCESS, leg.tick(ctx));
    }

    @Test
    void withEveryTargetAPitTheLegFails() {
        RunAway leg = new RunAway(List.of(PIT));
        leg.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        ctx.mover.route = route(PIT).trap();
        assertEquals(TaskStatus.FAILED, leg.tick(ctx));
        assertTrue(leg.failureDetail().contains("no way out"), leg.failureDetail());
    }

    @Test
    void aClearRouteIsNeverSecondGuessed() {
        RunAway leg = new RunAway(List.of(FIELD, OTHER));
        leg.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        ctx.mover.route = route(FIELD);
        assertEquals(TaskStatus.RUNNING, leg.tick(ctx));
        assertEquals(TaskStatus.RUNNING, leg.tick(ctx));
        assertEquals(1, ctx.mover.moveToCalls);
        assertEquals(0, ctx.mover.stopCalls);
    }

    @Test
    void anEscapeLegCarriesEveryPlaceWorthRunningToBestFirst() {
        ctx.seed(new Random(3));
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(new Pos(0, 64, -6), 6, false));
        List<Task> plan = FleeStepTest.method("escape").decompose(ctx);
        RunAway leg = assertInstanceOf(RunAway.class, plan.get(0));
        assertTrue(leg.targets().size() >= 8, "straight away, and the cells of the fan");
        assertEquals(leg.targets().size(), new java.util.HashSet<>(leg.targets()).size(),
                "no target twice: a pit found once is not searched again");
        assertTrue(leg.targets().get(0).z() > 0, "the first runs from it: " + leg.targets());
        assertInstanceOf(LookBack.class, plan.get(1));
    }

    private static Path route(Pos to) {
        return new Path(List.of(new Waypoint(to.x(), to.y(), to.z(), MoveType.WALK)), true);
    }
}
