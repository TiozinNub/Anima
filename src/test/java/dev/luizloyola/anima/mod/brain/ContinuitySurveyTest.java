package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeGrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.EscapeStep;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import dev.luizloyola.anima.core.brain.task.PlaceStation;
import dev.luizloyola.anima.core.brain.task.SurveyArea;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.continuity.StateGraph;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Report only: real plans, saved and loaded through the real codecs mid-run, and what the graph
 * check finds different. Never fails — it is the measurement the continuity ladder's first step
 * asks for; each finding becomes an assertion where it is fixed.
 */
class ContinuitySurveyTest {

    @BeforeAll
    static void codecs() {
        AnimaTasks.install();
    }

    private static TaskExecutor reloaded(TaskExecutor live) {
        var codec = BrainState.executor();
        var saved = codec.encodeStart(JsonOps.INSTANCE, live.snapshot()).getOrThrow();
        TaskExecutor restored = new TaskExecutor();
        restored.restore(codec.parse(JsonOps.INSTANCE, saved).getOrThrow());
        return restored;
    }

    private static void report(String scenario, Object live, Object restored) {
        StateGraph before = StateGraph.capture(live);
        List<String> lost = before.diff(StateGraph.capture(restored));
        System.out.println("CONTINUITY " + scenario + ": " + lost.size() + " difference(s)"
                + (before.opaque().isEmpty() ? "" : ", unseen: " + before.opaque()));
        lost.forEach(line -> System.out.println("CONTINUITY   " + line));
    }

    /** Ticks the executor, landing every walk it orders on the next tick. */
    private static void drive(TaskExecutor executor, FakeContext ctx, int ticks) {
        for (int i = 0; i < ticks; i++) {
            executor.tick(ctx);
            if (ctx.mover.moveToCalls > 0 && ctx.mover.state() == MoveState.IDLE) {
                ctx.mover.setState(MoveState.MOVING);
            }
        }
    }

    @Test
    void aStationBeingPutDown() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.inventory.add(ItemStack.of(Workbench.ITEM_ID, 1, 64));
        TaskExecutor live = new TaskExecutor();
        live.run(new PlaceStation(Workbench.POI, Workbench.ITEM_ID, new Pos(6, 64, 0)), ctx);
        drive(live, ctx, 3);
        report("place station, walking to it", live, reloaded(live));
    }

    @Test
    void anEscapeCuttingAStair() {
        FakeContext ctx = new FakeContext();
        int feet = 64;
        ctx.percepts.position = new Pos(0, feet, 0);
        ctx.percepts.confinement = new Confinement(true, 1);
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int y = feet; y <= feet + 3; y++) {
                ctx.percepts.blocks.set(d[0], y, d[1], BlockKind.OTHER);
            }
        }
        TaskExecutor live = new TaskExecutor();
        live.run(new EscapeStep(), ctx);
        drive(live, ctx, 2);
        report("escape, mid-cut", live, reloaded(live));
    }

    @Test
    void aSurveyMidWalk() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        TaskExecutor live = new TaskExecutor();
        live.run(new SurveyArea(new Region(new Pos(0, 63, 0), new Pos(31, 70, 31)),
                FakeGrowthRule.THICKET), ctx);
        drive(live, ctx, 4);
        report("survey, mid-walk", live, reloaded(live));
    }

    @Test
    void theSetbacksAndTheirRefusals() {
        var setbacks = new dev.luizloyola.anima.core.brain.sense.Setbacks();
        setbacks.record(new Pos(1, 2, 3), dev.luizloyola.anima.core.brain.sense.Setbacks.Kind.WEDGED, 5);
        setbacks.refuse(new Pos(1, 2, 3), new Pos(4, 2, 3), 6);
        var saved = BrainState.SETBACKS.encodeStart(JsonOps.INSTANCE, setbacks.snapshot()).getOrThrow();
        var restored = new dev.luizloyola.anima.core.brain.sense.Setbacks();
        restored.restore(BrainState.SETBACKS.parse(JsonOps.INSTANCE, saved).getOrThrow());
        report("setbacks", setbacks, restored);
    }
}
