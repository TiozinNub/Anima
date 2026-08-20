package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Be at a store: walk to one, or make one — and where a made one goes is settlement policy. */
class EnsureStoreTest {

    private static void remember(FakeContext ctx, dev.luizloyola.anima.core.brain.knowledge.PoiKind kind,
            Pos at) {
        ctx.knowledge.note(new PoiMemory(kind, at, Region.of(at), 1, false, 0L), 64);
    }

    /** Where a decomposition would put the chest down. */
    private static Pos placedAt(List<Task> steps) {
        return steps.stream()
                .filter(step -> step instanceof FoundPlace)
                .map(step -> ((FoundPlace) step).anchor())
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing founds a place: " + steps));
    }

    @Test
    void walkingIsPricedAtTheDistanceAndBuildingIsFlat() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, Store.POI, new Pos(50, 64, 0));

        EnsureStore goal = new EnsureStore();
        Method walk = goal.methods().get(0);
        Method build = goal.methods().get(1);

        assertTrue(walk.applicable(ctx));
        assertEquals(50.0, walk.estimateCost(ctx), 0.5);
        assertEquals(EnsureStore.PLACE_COST, build.estimateCost(ctx), 0.0001,
                "building costs the same wherever you are — that is what lets distance decide");
    }

    @Test
    void aMadeStoreGoesBesideAKnownPartyPlace() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, Workbench.POI, new Pos(20, 64, 0));

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertTrue(steps.stream().anyMatch(step -> step instanceof GoTo),
                "a known bench inside the radius is walked to before the chest goes down");
        assertTrue(Store.distance(placedAt(steps), new Pos(20, 64, 0)) <= 2.5,
                "the chest lands next to the bench, not where the pack happened to fill");
    }

    @Test
    void itNeverBuildsInTheCellItWalksInto() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, Workbench.POI, new Pos(20, 64, 0));

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        GoTo walk = (GoTo) steps.stream().filter(step -> step instanceof GoTo).findFirst()
                .orElseThrow();
        Pos spot = placedAt(steps);

        assertFalse(walk.x() == spot.x() && walk.y() == spot.y() && walk.z() == spot.z(),
                "one cell to stand in and a different one to build in — using one for both put a "
                        + "chest inside the settler, then livelocked the goal when the placer "
                        + "started refusing (in-world, 2026-08-20)");
    }

    @Test
    void itDoesNotWalkToTheCellItIsStandingIn() {
        FakeContext ctx = new FakeContext();
        Pos bench = new Pos(20, 64, 0);
        remember(ctx, Workbench.POI, bench);
        // Standing on the cell standableBeside would pick — the nearest free side of the bench.
        ctx.percepts.position = EnsureTable.WalkToKnown.standableBeside(bench, ctx);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertFalse(steps.stream().anyMatch(step -> step instanceof GoTo),
                "the navigator answers PATHING to the cell you already occupy and never arrives, "
                        + "so asking for that walk hangs the goal (in-world, 2026-08-20)");
        assertTrue(steps.stream().anyMatch(step -> step instanceof PlaceBlock),
                "and it still builds");
    }

    @Test
    void withNothingKnownItGoesUnderfoot() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertTrue(Store.distance(placedAt(steps), new Pos(0, 64, 0)) <= 2.5);
        assertFalse(steps.stream().anyMatch(step -> step instanceof GoTo), "nothing to walk to");
    }

    @Test
    void aBenchBeyondTheRadiusIsNotWorthWalkingToJustToBuildBesideIt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, Workbench.POI, new Pos(500, 64, 0));

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertFalse(steps.stream().anyMatch(step -> step instanceof GoTo),
                "past stores.found_radius a settlement is somewhere else, and this chest is here");
    }

    @Test
    void theChestItPlacesIsThePartysFromTheMomentItLands() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertTrue(steps.stream().anyMatch(step -> step instanceof FoundPlace),
                "FoundPlace writes a COMMUNAL row — a placed container belongs to the party, "
                        + "never to whoever put it down");
    }

    @Test
    void aStoreJustFoundFullIsNotOneToWalkTo() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.time = 1_000L;
        Pos stuffed = new Pos(5, 64, 0);
        remember(ctx, Store.POI, stuffed);
        ctx.knowledge.avoid(Store.POI, stuffed, 2_000L);

        EnsureStore goal = new EnsureStore();

        assertFalse(goal.methods().get(0).applicable(ctx),
                "a chest this body just found full is not somewhere to walk — without this the "
                        + "achieve-loop re-opens it every round until the cap");
        assertTrue(goal.methods().get(1).applicable(ctx), "so building one is what is left");
    }

    @Test
    void anAvoidMarkExpiresAndTheStoreComesBack() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.time = 3_000L;
        Pos stuffed = new Pos(5, 64, 0);
        remember(ctx, Store.POI, stuffed);
        ctx.knowledge.avoid(Store.POI, stuffed, 2_000L);

        assertTrue(new EnsureStore().methods().get(0).applicable(ctx),
                "the belief was never wrong — only a timer un-blinds it");
    }

    @Test
    void nothingIsEverBuiltIntoABody() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);

        Pos spot = placedAt(new EnsureStore().methods().get(1).decompose(ctx));

        assertFalse(PlaceBlock.occupied(ctx, spot),
                "the chooser must not hand back a cell somebody is standing in — found in-world "
                        + "on 2026-08-20, when a settler put a workbench inside Luiz");
        assertTrue(PlaceBlock.occupied(ctx, ctx.percepts.position),
                "and the asking body counts as an occupant of its own feet");
    }

    @Test
    void beingRidOfCargoIsWhatSatisfiesTheGoalAbove() {
        FakeContext ctx = new FakeContext();
        assertTrue(new PutAwaySurplus().satisfied(ctx), "an empty pack has nothing to put away");

        ctx.percepts.inventory().set(0, ItemStack.of("minecraft:oak_log", 64, 64));
        assertFalse(new PutAwaySurplus().satisfied(ctx));
    }

    @Test
    void theGoalDecomposesToGettingThereThenEmptyingThePack() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory().set(0, ItemStack.of("minecraft:oak_log", 64, 64));

        List<Task> steps = new PutAwaySurplus().methods().get(0).decompose(ctx);

        assertEquals(2, steps.size());
        assertTrue(steps.get(0) instanceof EnsureStore);
        assertTrue(steps.get(1) instanceof PutItems);
    }
}
