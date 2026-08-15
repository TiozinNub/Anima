package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Being at a workbench: satisfied is memory VERIFIED BY the WORLD (a griefed table is forgotten,
 * not believed), walking beats placing when a table is near, and the make-and-place plan carries
 * its own memory write so the very next subtask can find the bench.
 */
class EnsureTableTest {

    private final FakeContext ctx = new FakeContext();

    private void standAt(int x, int z) {
        ctx.percepts.position = new Pos(x, FakeProbe.GROUND_Y + 1, z);
    }

    private void rememberTable(int x, int z) {
        ctx.knowledge.note(Workbench.memoryOf(new Pos(x, FakeProbe.GROUND_Y + 1, z), 0),
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    private void realTable(int x, int z) {
        ctx.percepts.blocks.set(x, FakeProbe.GROUND_Y + 1, z, Workbench.BLOCK);
    }

    @Test
    void satisfiedOnlyByARememberedTableTheWorldStillBacks() {
        standAt(10, 10);
        EnsureTable goal = new EnsureTable();
        assertFalse(goal.satisfied(ctx), "no memory, no bench");

        rememberTable(12, 10);
        realTable(12, 10);
        assertTrue(goal.satisfied(ctx), "a real table two blocks away is 'at the bench'");
    }

    @Test
    void aGriefedTableIsForgottenNotBelieved() {
        standAt(10, 10);
        rememberTable(12, 10); // remembered, but the world holds no block there
        assertFalse(new EnsureTable().satisfied(ctx));
        assertTrue(ctx.knowledge.nearest(Workbench.POI, new Pos(10, 64, 10)).isEmpty(),
                "the lie was dropped on the spot");
    }

    @Test
    void aNearbyKnownTableIsWalkedToRatherThanDuplicated() {
        standAt(10, 10);
        rememberTable(20, 10);
        realTable(20, 10);
        EnsureTable goal = new EnsureTable();
        Method walk = goal.methods().get(0);
        Method place = goal.methods().get(1);
        assertTrue(walk.applicable(ctx));
        assertTrue(walk.estimateCost(ctx) < place.estimateCost(ctx),
                "ten blocks of walk beats crafting a second table — settlements share benches");
        List<Task> plan = walk.decompose(ctx);
        GoTo go = assertInstanceOf(GoTo.class, plan.get(0));
        assertTrue(Math.abs(go.x() - 20) <= 1 && Math.abs(go.z() - 10) <= 1,
                "the walk targets a cell BESIDE the bench, not the bench block itself");
    }

    @Test
    void withNoTableKnownThePlanMakesPlacesAndRemembersOne() {
        standAt(10, 10);
        EnsureTable goal = new EnsureTable();
        assertFalse(goal.methods().get(0).applicable(ctx), "nothing known to walk to");
        List<Task> plan = goal.methods().get(1).decompose(ctx);

        ObtainItem table = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertTrue(table.spec().matches(Workbench.ITEM_ID));
        PlaceBlock put = assertInstanceOf(PlaceBlock.class, plan.get(1));
        NotePlace note = assertInstanceOf(NotePlace.class, plan.get(2));
        assertEquals(put.target(), note.anchor(),
                "the memory is written exactly where the block went — the next subtask needs it");
        assertEquals(FakeProbe.GROUND_Y + 1, put.target().y(), "on the ground, beside the body");
    }
}
