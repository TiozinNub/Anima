package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A table put down to craft on is taken back after the craft; a table the body did not put down
 * for itself — a base's, a player's — is never touched.
 */
class PackUpTableTest {

    private static final int Y = FakeProbe.GROUND_Y + 1;

    private final FakeContext ctx = new FakeContext();

    private Pos table(int x, int z) {
        Pos at = new Pos(x, Y, z);
        ctx.percepts.blocks.set(x, Y, z, Workbench.BLOCK);
        ctx.knowledge.note(Workbench.memoryOf(at, 0), AgentKnowledge.maxPerKind(ctx.profile()));
        return at;
    }

    /** Runs the goal, finishing each swing the arm begins by taking the block out of the world. */
    private Optional<TaskStatus> run(Task task) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(task, ctx);
        for (int tick = 0; tick < 200 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos at = ctx.breaker.target;
                ctx.percepts.blocks.set(at.x(), at.y(), at.z(), BlockKind.AIR);
                ctx.breaker.state = BreakState.FINISHED;
            }
            executor.tick(ctx);
        }
        return executor.lastStatus();
    }

    @Test
    void aTableItPutDownToCraftOnIsBrokenAndStruckOff() {
        ctx.percepts.position = new Pos(10, Y, 10);
        Pos mine = table(11, 10);
        assertEquals(TaskStatus.SUCCESS, new NoteFieldTable(mine.x(), mine.y(), mine.z()).tick(ctx));

        assertEquals(Optional.of(TaskStatus.SUCCESS), run(new PackUpTable()));

        assertEquals(List.of(mine), ctx.breaker.targets);
        assertTrue(ctx.fieldTables.snapshot().isEmpty(), "taken back, so no longer left anywhere");
        assertTrue(ctx.knowledge.nearest(Workbench.POI, mine).isEmpty(),
                "nothing to walk back to for the next craft");
    }

    @Test
    void aTableItDidNotPutDownForItselfStays() {
        ctx.percepts.position = new Pos(10, Y, 10);
        table(11, 10); // the base's, or a player's: remembered, never noted as its own

        assertEquals(Optional.of(TaskStatus.SUCCESS), run(new PackUpTable()));

        assertTrue(ctx.breaker.targets.isEmpty());
    }

    @Test
    void itsOwnTableOutOfReachIsLeftForALaterCraft() {
        ctx.percepts.position = new Pos(10, Y, 10);
        Pos far = table(30, 10);
        ctx.fieldTables.record(far);

        assertEquals(Optional.of(TaskStatus.SUCCESS), run(new PackUpTable()));

        assertTrue(ctx.breaker.targets.isEmpty());
        assertEquals(List.of(far), ctx.fieldTables.snapshot(), "still its own, still to pick up");
    }

    @Test
    void oneSomebodyElseBrokeIsStruckOffWithoutASwing() {
        ctx.percepts.position = new Pos(10, Y, 10);
        Pos gone = new Pos(11, Y, 10);
        ctx.fieldTables.record(gone);

        assertEquals(Optional.of(TaskStatus.SUCCESS), run(new PackUpTable()));

        assertTrue(ctx.breaker.targets.isEmpty());
        assertTrue(ctx.fieldTables.snapshot().isEmpty());
    }
}
