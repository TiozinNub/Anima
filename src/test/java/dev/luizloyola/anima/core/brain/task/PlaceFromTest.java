package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceFromTest {

    private static final Placing PLANKS = Placing.of("minecraft:oak_planks", new Pos(8, 64, 0));
    private static final Pos PLANNED = new Pos(5, 64, 0);

    private static Being builder(Pos at) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.AGENT, "person", "Bia", null, at, 5,
                Being.HUMANOID_EYE_HEIGHT, false, 1, 0, false, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    private static GoTo walk(FakeContext ctx) {
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), PLANNED, false);
        return assertInstanceOf(GoTo.class, place.methods().get(0).decompose(ctx).get(0));
    }

    @Test
    void theBodyWalksToThePlannedStand() {
        FakeContext ctx = new FakeContext();
        GoTo walk = walk(ctx);
        assertEquals(PLANNED, new Pos(walk.x(), walk.y(), walk.z()));
    }

    @Test
    void aStandAnotherBodyHoldsIsLeftToIt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.beings = List.of(builder(PLANNED));
        GoTo walk = walk(ctx);
        assertNotEquals(PLANNED, new Pos(walk.x(), walk.y(), walk.z()), "the other builder stands there");
    }

    /** Runs the last way, as the executor does once walking and placing have failed. */
    private static String whyNot(PlaceFrom place, FakeContext ctx) {
        Task why = place.methods().get(3).decompose(ctx).get(0);
        PlaceFrom.WhyNot whyNot = assertInstanceOf(PlaceFrom.WhyNot.class, why);
        assertEquals(TaskStatus.FAILED, whyNot.tick(ctx));
        return whyNot.failureDetail();
    }

    @Test
    void aCellNoStandReachesSaysSoAndWhatWasWrongWithThePlannedStand() {
        FakeContext ctx = new FakeContext();
        PlaceFrom place = new PlaceFrom(Placing.of("minecraft:oak_slab", new Pos(8, 72, 0)), List.of(), PLANNED, false);
        assertFalse(place.methods().get(0).applicable(ctx), "y 72 is out of reach from the ground");
        assertEquals("no stand reaches it; the planned one at (5, 64, 0) — stands, but not in reach or in the work",
                whyNot(place, ctx));
    }

    @Test
    void aWalkThatNeverGotThereSaysWhereTheBodyStood() {
        FakeContext ctx = new FakeContext();
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), PLANNED, false);
        place.methods().get(0).decompose(ctx);
        assertEquals("never got to the stand at (5, 64, 0), stood at (0, 64, 0)", whyNot(place, ctx));
    }

    @Test
    void atTheStandItIsThePlacersRefusal() {
        FakeContext ctx = new FakeContext();
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), PLANNED, false);
        place.methods().get(0).decompose(ctx);
        ctx.percepts.position = PLANNED;
        assertEquals("the placer refused it from (5, 64, 0)", whyNot(place, ctx));
    }

    private static double reach(Pos stand, Pos cell) {
        double dx = cell.x() - stand.x();
        double dy = cell.y() + 0.5 - (stand.y() + Being.HUMANOID_EYE_HEIGHT);
        double dz = cell.z() - stand.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void aStandAtTheEdgeOfReachIsNotTaken() {
        // 4.15 from the cell: inside the placer's 4.5, outside it for a body off its cell's middle.
        Pos edge = new Pos(4, 64, 0);
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), edge, false);
        GoTo walk = assertInstanceOf(GoTo.class, place.methods().get(0).decompose(new FakeContext()).get(0));
        Pos to = new Pos(walk.x(), walk.y(), walk.z());
        assertNotEquals(edge, to);
        assertTrue(reach(to, PLANKS.cell()) <= PlaceFrom.STAND_REACH, to + " is " + reach(to, PLANKS.cell()));
    }

    @Test
    void aStandTheWalkDidNotGetToIsNotChosenAgain() {
        FakeContext ctx = new FakeContext();
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), PLANNED, false);
        place.methods().get(0).decompose(ctx);
        // The body is still where it began: the walk did not get there.
        assertTrue(place.methods().get(1).applicable(ctx));
        GoTo again = assertInstanceOf(GoTo.class, place.methods().get(1).decompose(ctx).get(0));
        assertNotEquals(PLANNED, new Pos(again.x(), again.y(), again.z()));
        assertEquals(List.of(PLANNED), place.walkedOff());
    }

    @Test
    void aBodyWhoseBoxReachesIntoTheCellStepsOffFirst() {
        FakeContext ctx = new FakeContext();
        Pos beside = new Pos(7, 64, 0);
        ctx.percepts.position = beside;
        ctx.percepts.footprint = new Region(beside, new Pos(8, 65, 0));
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), null, false);
        GoTo walk = assertInstanceOf(GoTo.class, place.methods().get(0).decompose(ctx).get(0),
                "placed from here, the placer would refuse it");
        assertNotEquals(beside, new Pos(walk.x(), walk.y(), walk.z()));
    }

    @Test
    void anotherStandIsOnlyForAWalkThatFailed() {
        FakeContext ctx = new FakeContext();
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), PLANNED, false);
        place.methods().get(0).decompose(ctx);
        ctx.percepts.position = PLANNED;
        assertFalse(place.methods().get(1).applicable(ctx), "at the stand, the placer refused: no other walk");
    }

    private static final ItemSpec DIRT = ItemSpec.anyOf(java.util.Set.of("minecraft:dirt"));
    private static final Placing HIGH = Placing.of("minecraft:oak_slab", new Pos(8, 70, 0));

    @Test
    void aCellNoGroundStandReachesIsPlacedFromAPillarAndTheBodyComesDown() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:dirt", 5, 64));
        PlaceFrom place = new PlaceFrom(HIGH, List.of(), PLANNED, false, DIRT);
        assertFalse(place.methods().get(0).applicable(ctx), "nothing on the ground reaches y 70");
        assertTrue(place.methods().get(2).applicable(ctx));
        List<Task> steps = place.methods().get(2).decompose(ctx);
        GoTo walk = assertInstanceOf(GoTo.class, steps.get(0));
        Pos base = new Pos(walk.x(), walk.y(), walk.z());
        long rises = steps.stream().filter(Rise.class::isInstance).count();
        assertTrue(rises >= 1 && rises <= PlaceFrom.MAX_PILLAR, steps.toString());
        assertTrue(steps.stream().filter(Rise.class::isInstance).allMatch(r -> ((Rise) r).recorded()),
                "a scaffold is recorded");
        List<BreakBlock> down = steps.stream().filter(BreakBlock.class::isInstance).map(BreakBlock.class::cast).toList();
        assertEquals(rises, down.size(), "every block laid is broken again");
        assertEquals(new Pos(base.x(), base.y(), base.z()), down.get(down.size() - 1).target(),
                "the last block broken is the bottom one");
    }

    @Test
    void noScaffoldOrNothingCarriedIsNoPillar() {
        FakeContext ctx = new FakeContext();
        assertFalse(new PlaceFrom(HIGH, List.of(), PLANNED, false).methods().get(2).applicable(ctx));
        assertFalse(new PlaceFrom(HIGH, List.of(), PLANNED, false, DIRT).methods().get(2).applicable(ctx),
                "no dirt in the pack");
    }

    @Test
    void aRiseLaysWhatTheBodyCarriesAndSaysItIsRecorded() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:dirt", 5, 64));
        Rise rise = new Rise(DIRT, true);
        assertEquals(TaskStatus.RUNNING, rise.tick(ctx));
        assertEquals("minecraft:dirt", ctx.riser.lastItem);
        assertTrue(ctx.riser.lastRecorded);
        ctx.riser.state = dev.luizloyola.anima.core.brain.act.RiseState.RISEN;
        assertEquals(TaskStatus.SUCCESS, rise.tick(ctx));
    }
}
