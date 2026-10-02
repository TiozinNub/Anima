package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Being at a workbench: satisfied is memory VERIFIED BY the WORLD (a griefed table is forgotten,
 * not believed), walking beats placing when a table is near, and the make-and-place plan carries
 * its own memory write so the very next subtask can find the bench, without claiming it for the
 * party.
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
        assertTrue(Math.abs(go.x() - 20) <= 1 && Math.abs(go.z() - 10) <= 1
                        && !(go.x() == 20 && go.z() == 10),
                "the walk targets a cell BESIDE the bench, not the bench block itself");
    }

    @Test
    void withNoTableKnownThePlanMakesPlacesAndNotesOneAsItsOwn() {
        standAt(10, 10);
        EnsureTable goal = new EnsureTable();
        assertFalse(goal.methods().get(0).applicable(ctx), "nothing known to walk to");
        List<Task> plan = goal.methods().get(1).decompose(ctx);

        ObtainItem table = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertTrue(table.spec().matches(Workbench.ITEM_ID));
        PlaceBlock put = assertInstanceOf(PlaceBlock.class, plan.get(1));
        NoteFieldTable noted = assertInstanceOf(NoteFieldTable.class, plan.get(2));
        assertEquals(put.target(), noted.anchor(),
                "noted exactly where the block went — the next subtask needs it");
        assertTrue(plan.stream().noneMatch(FoundPlace.class::isInstance),
                "the body's own, not the party's: no claim");
        assertEquals(FakeProbe.GROUND_Y + 1, put.target().y(), "on the ground, beside the body");
    }

    @Test
    void aCellTheBodysOwnBoxReachesIntoIsNeverTheSpot() {
        standAt(10, 10);
        int y = FakeProbe.GROUND_Y + 1;
        // 0.04 into the cell east of the feet, as the watch run's settler stood (2026-10-01).
        ctx.percepts.footprint = new Region(new Pos(10, y, 10), new Pos(11, y + 1, 10));
        List<Task> plan = new EnsureTable().methods().get(1).decompose(ctx);
        PlaceBlock put = assertInstanceOf(PlaceBlock.class, plan.get(1));
        assertNotEquals(new Pos(11, y, 10), put.target(), "the placer would refuse it");
        assertFalse(ctx.percepts.footprint.contains(put.target()));
    }

    @Test
    void aTableCarriedButRefusedSaysWhyOnceAndThenTriesAgain() {
        standAt(10, 10);
        EnsureTable goal = new EnsureTable();
        Method say = goal.methods().get(2);
        assertFalse(say.applicable(ctx), "nothing tried yet");
        List<Task> plan = goal.methods().get(1).decompose(ctx);
        Pos spot = assertInstanceOf(PlaceBlock.class, plan.get(1)).target();
        assertFalse(say.applicable(ctx), "the table was never had: the obtain says why, not this");

        ctx.percepts.inventory.add(ItemStack.of(Workbench.ITEM_ID, 1, 64));
        assertTrue(say.applicable(ctx));
        PlaceFrom.WhyNot why = assertInstanceOf(PlaceFrom.WhyNot.class, say.decompose(ctx).get(0));
        assertEquals(spot, why.placing().cell());
        assertEquals(TaskStatus.FAILED, why.tick(ctx));
        assertEquals("the placer refused it from (10, 64, 10)", why.failureDetail());
        assertFalse(say.applicable(ctx), "said once; the next round places before it says anything");
    }
}
