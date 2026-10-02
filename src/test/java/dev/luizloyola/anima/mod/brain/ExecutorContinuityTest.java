package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.AtOneBench;
import dev.luizloyola.anima.core.brain.task.EscapeStep;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.GoTo;
import dev.luizloyola.anima.core.brain.task.HandlingPhase;
import dev.luizloyola.anima.core.brain.task.PlaceStation;
import dev.luizloyola.anima.core.brain.task.PutItems;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.Try;
import dev.luizloyola.anima.core.continuity.StateGraph;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A plan saved and loaded mid-run comes back as the plan it was: one tree, the frames running the
 * objects the root holds, not copies of them (continuity rule 5, 2026-09-25).
 */
class ExecutorContinuityTest {

    @BeforeAll
    static void codecs() {
        AnimaTasks.install();
    }

    private static List<String> lost(TaskExecutor live) {
        var codec = BrainState.executor();
        var saved = codec.encodeStart(JsonOps.INSTANCE, live.snapshot()).getOrThrow();
        TaskExecutor restored = new TaskExecutor();
        restored.restore(codec.parse(JsonOps.INSTANCE, saved).getOrThrow());
        return StateGraph.capture(live).diff(StateGraph.capture(restored));
    }

    private static TaskExecutor running(Task plan, FakeContext ctx, int ticks) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(plan, ctx);
        for (int i = 0; i < ticks; i++) {
            executor.tick(ctx);
            if (ctx.mover.moveToCalls > 0 && ctx.mover.state() == MoveState.IDLE) {
                ctx.mover.setState(MoveState.MOVING);
            }
        }
        return executor;
    }

    private static FakeContext carryingABench() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.inventory.add(ItemStack.of(Workbench.ITEM_ID, 1, 64));
        return ctx;
    }

    @Test
    void twoFramesDeepComesBackAsOneTree() {
        FakeContext ctx = carryingABench();
        TaskExecutor live = running(
                new PlaceStation(Workbench.POI, Workbench.ITEM_ID, new Pos(6, 64, 0)), ctx, 3);
        assertEquals(List.of(), lost(live));
    }

    /** A wrapper's child is the decomposition's first task: the two must stay one object. */
    @Test
    void aWrappersChildIsTheTaskThatRuns() {
        FakeContext ctx = carryingABench();
        TaskExecutor live = running(
                new Try(new PlaceStation(Workbench.POI, Workbench.ITEM_ID, new Pos(6, 64, 0))), ctx, 3);
        assertEquals(List.of(), lost(live));
    }

    /** A run at one bench comes back as one tree, still packing up after its last errand. */
    @Test
    void aRunAtOneBenchComesBackWhole() {
        FakeContext ctx = carryingABench();
        TaskExecutor live = running(new AtOneBench(List.of(
                new PlaceStation(Workbench.POI, Workbench.ITEM_ID, new Pos(6, 64, 0)),
                new GoTo(1, 64, 1))), ctx, 3);
        assertEquals(List.of(), lost(live));

        var codec = BrainState.executor();
        TaskExecutor restored = new TaskExecutor();
        restored.restore(codec.parse(JsonOps.INSTANCE,
                codec.encodeStart(JsonOps.INSTANCE, live.snapshot()).getOrThrow()).getOrThrow());
        assertTrue(restored.tablesPackedUpAbove(), "a craft after the restart leaves its table up");
    }

    @Test
    void anEscapeMidCutComesBackWhole() {
        FakeContext ctx = new FakeContext();
        int feet = 64;
        ctx.percepts.position = new Pos(0, feet, 0);
        ctx.percepts.confinement = new Confinement(true, 1);
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int y = feet; y <= feet + 3; y++) {
                ctx.percepts.blocks.set(d[0], y, d[1], BlockKind.OTHER);
            }
        }
        assertEquals(List.of(), lost(running(new EscapeStep(), ctx, 2)));
    }

    /** A stow saved while moving kept its phase and lost its store, and crashed on the next tick. */
    @Test
    void aStowMidMoveKeepsTheStoreItSettledOn() {
        PutItems live = PutItems.stow().resume(HandlingPhase.MOVE, 0, 3).settledOn(new Pos(4, 64, 2));
        var codec = TaskCodecs.codec();
        Task restored = codec.parse(JsonOps.INSTANCE,
                codec.encodeStart(JsonOps.INSTANCE, live).getOrThrow()).getOrThrow();
        assertEquals(List.of(), StateGraph.capture(live).diff(StateGraph.capture(restored)));
    }
}
