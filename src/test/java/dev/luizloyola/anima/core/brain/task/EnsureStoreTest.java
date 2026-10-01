package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Be at a store of the body's depot: walk to one in its area, or make one at its hint. */
class EnsureStoreTest {

    private static final Pos HINT = new Pos(10, 64, 10);

    /** A depot of the chunks given, or of the hint's own chunk alone. */
    private static void home(FakeContext ctx, Pos hint, ChunkKey... chunks) {
        Set<ChunkKey> area = chunks.length == 0
                ? Set.of(ChunkKey.at(ChunkKey.OVERWORLD, hint.x(), hint.z()))
                : Set.of(chunks);
        ctx.depot = Optional.of(new Depot.Site(hint, area));
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(ChunkKey.OVERWORLD, x, z);
    }

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
    void aStoreIsNeverMadeAnywhereButTheDepot() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(200, 64, 200);
        home(ctx, HINT);

        Pos spot = placedAt(new EnsureStore().methods().get(1).decompose(ctx));

        assertTrue(Store.distance(spot, HINT) <= 2.5,
                "no base, no offloading (Luiz, 2026-09-30): a chest at a scouting stop held 458 "
                        + "items 450 blocks from where its maker settled");
    }

    @Test
    void withoutADepotThereIsNoStoreToBeAt() {
        FakeContext ctx = new FakeContext();
        Pos chest = new Pos(1, 64, 0);
        ctx.claim(Store.POI, chest);
        ctx.percepts.blocks.set(chest.x(), chest.y(), chest.z(), Store.BLOCK);
        EnsureStore goal = new EnsureStore();

        assertFalse(goal.satisfied(ctx), "a chest at hand is nobody's home without a depot");
        assertTrue(goal.methods().stream().noneMatch(way -> way.applicable(ctx)),
                "and no way opens one: no base, no offloading (Luiz, 2026-09-30)");
    }

    @Test
    void anyStoreInTheAreaIsHomeHoweverFarFromTheHint() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(74, 64, 6);
        Pos far = new Pos(75, 64, 5);
        ctx.claim(Store.POI, far);
        ctx.percepts.blocks.set(far.x(), far.y(), far.z(), Store.BLOCK);
        EnsureStore goal = new EnsureStore();

        home(ctx, HINT);
        assertTrue(goal.methods().get(1).applicable(ctx), "outside the area it is not home's");

        home(ctx, HINT, chunk(0, 0), chunk(1, 0), chunk(2, 0), chunk(3, 0), chunk(4, 0));
        assertTrue(goal.methods().get(0).applicable(ctx),
                "65 blocks from the hint, but the area grew over it — the same goal sees it");
        assertFalse(goal.methods().get(1).applicable(ctx));
        assertTrue(goal.satisfied(ctx));
    }

    @Test
    void itNeverBuildsInTheCellItWalksInto() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);

        home(ctx, HINT);

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
        Pos ground = Ground.near(ctx, HINT, 2);
        // Standing on the cell standableBeside would pick — the nearest free side of the spot.
        ctx.percepts.position = EnsureTable.WalkToKnown.standableBeside(ground, ctx);
        home(ctx, HINT);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertFalse(steps.stream().anyMatch(step -> step instanceof GoTo),
                "the navigator answers PATHING to the cell you already occupy and never arrives, "
                        + "so asking for that walk hangs the goal (in-world, 2026-08-20)");
        assertTrue(steps.stream().anyMatch(step -> step instanceof PlaceBlock),
                "and it still builds");
    }

    @Test
    void theChestItPlacesIsThePartysFromTheMomentItLands() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);

        home(ctx, HINT);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);

        assertTrue(steps.stream().anyMatch(step -> step instanceof FoundPlace),
                "FoundPlace writes a COMMUNAL row — a placed container belongs to the party, "
                        + "never to whoever put it down");
    }

    @Test
    void aChestAtHandOutsideTheAreaIsNotHome() {
        FakeContext ctx = new FakeContext();
        Pos yard = new Pos(10, 64, 10);
        Pos other = new Pos(101, 64, 100);
        ctx.percepts.position = new Pos(100, 64, 100);
        for (Pos chest : List.of(yard, other)) {
            ctx.claim(Store.POI, chest);
            ctx.percepts.blocks.set(chest.x(), chest.y(), chest.z(), Store.BLOCK);
        }

        home(ctx, yard);
        assertFalse(new EnsureStore().satisfied(ctx),
                "a home chest somewhere is not the chest at hand being home's");
        home(ctx, other);
        assertTrue(new EnsureStore().satisfied(ctx));
    }

    @Test
    void aFullChestBesideTheBodySendsTheStowOnToAFreeOne() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        Pos full = new Pos(1, 64, 0);
        Pos free = new Pos(16, 64, 0);
        for (Pos chest : List.of(full, free)) {
            ctx.claim(Store.POI, chest);
            ctx.percepts.blocks.set(chest.x(), chest.y(), chest.z(), Store.BLOCK);
            ctx.containers.boxes.put(chest, new java.util.ArrayList<>());
        }
        ctx.containers.full.add(full);
        for (int slot = 0; slot < 3; slot++) {
            ctx.percepts.inventory().set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
        ctx.mover.setState(dev.luizloyola.anima.core.brain.act.MoveState.ARRIVED);
        home(ctx, new Pos(0, 64, 0), chunk(0, 0), chunk(1, 0));

        TaskExecutor executor = new TaskExecutor();
        executor.run(new PutAwaySurplus(0), ctx);
        int walks = 0;
        for (int tick = 0; tick < 2000 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            executor.tick(ctx);
            if (ctx.mover.moveToCalls > walks) { // the fake legs arrive at once
                walks = ctx.mover.moveToCalls;
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
            }
        }

        assertEquals(java.util.Optional.of(TaskStatus.SUCCESS), executor.lastStatus(),
                "found in-world 2026-09-27: 31 rounds of no store in reach, then the cap");
        assertEquals(3, ctx.containers.boxes.get(free).size(), "every stack in the free chest");
    }

    @Test
    void aStoreJustFoundFullIsNotOneToWalkTo() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.time = 1_000L;
        Pos stuffed = new Pos(5, 64, 0);
        ctx.claim(Store.POI, stuffed);
        ctx.knowledge.avoid(Store.POI, stuffed, 2_000L);
        home(ctx, stuffed);

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
        ctx.claim(Store.POI, stuffed);
        ctx.knowledge.avoid(Store.POI, stuffed, 2_000L);

        home(ctx, stuffed);

        assertTrue(new EnsureStore().methods().get(0).applicable(ctx),
                "the belief was never wrong — only a timer un-blinds it");
    }

    @Test
    void nothingIsEverBuiltIntoABody() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);

        home(ctx, ctx.percepts.position);

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
        assertTrue(new PutAwaySurplus(0).satisfied(ctx),
                "an empty pack has nothing to put away");

        ctx.percepts.inventory().set(0, ItemStack.of("minecraft:oak_log", 64, 64));
        assertFalse(new PutAwaySurplus(0).satisfied(ctx));
    }

    @Test
    void theGoalDecomposesToGettingThereThenEmptyingThePack() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory().set(0, ItemStack.of("minecraft:oak_log", 64, 64));

        List<Task> steps = new PutAwaySurplus(0).methods().get(0).decompose(ctx);

        assertEquals(2, steps.size());
        assertTrue(steps.get(0) instanceof EnsureStore);
        assertTrue(steps.get(1) instanceof PutItems);
    }

    @Test
    void aSecondChestGoesBesideTheFirstAndNeverOnTopOfIt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.time = 1_000L;
        Pos yard = new Pos(10, 64, 10);
        ctx.percepts.blocks.set(yard.x(), yard.y(), yard.z(), Store.BLOCK);
        ctx.claim(Store.POI, yard);
        ctx.knowledge.avoid(Store.POI, yard, 2_000L); // found full: a second one IS wanted here
        home(ctx, yard);

        Pos spot = placedAt(new EnsureStore().methods().get(1).decompose(ctx));

        assertEquals(yard.y(), spot.y(),
                "the lid of a chest is not ground to stand the next one on — in-world on "
                        + "2026-08-25 that read as four stores in one x/z, (-690, 72..75, 893), "
                        + "only the bottom one reachable");
        assertTrue(spot.x() != yard.x() || spot.z() != yard.z(),
                "and it still has to go somewhere: beside the full one, on the ground");
    }

    @Test
    void aHomeThatAlreadyHasAChestIsNotOneToOpenAgain() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        Pos yard = new Pos(10, 64, 10);
        ctx.percepts.blocks.set(yard.x(), yard.y(), yard.z(), Store.BLOCK);
        ctx.claim(Store.POI, yard);
        home(ctx, yard);

        EnsureStore goal = new EnsureStore();

        assertTrue(goal.methods().get(0).applicable(ctx), "home's chest is what to walk to");
        assertFalse(goal.methods().get(1).applicable(ctx),
                "four settlers each opening their own is what put four chests in one place "
                        + "in-world on 2026-08-25; the loser of the race has to walk to the "
                        + "winner's chest, not build beside it");
    }

    @Test
    void somebodyElsesChestAtHomeIsNotHomes() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        Pos yard = new Pos(10, 64, 10);
        ctx.percepts.blocks.set(yard.x(), yard.y(), yard.z(), Store.BLOCK);
        remember(ctx, Store.POI, yard);
        home(ctx, yard);

        EnsureStore goal = new EnsureStore();

        assertFalse(goal.methods().get(0).applicable(ctx), "not theirs to fill");
        assertTrue(goal.methods().get(1).applicable(ctx), "so the party opens its own");
    }

    @Test
    void aDepotAlwaysHasExactlyOneWayOpen() {
        Pos yard = new Pos(10, 64, 10);
        for (boolean chestThere : new boolean[]{false, true}) {
            FakeContext ctx = new FakeContext();
            ctx.percepts.position = new Pos(0, 64, 0);
            if (chestThere) {
                ctx.percepts.blocks.set(yard.x(), yard.y(), yard.z(), Store.BLOCK);
                ctx.claim(Store.POI, yard);
            }
            home(ctx, yard);
            EnsureStore goal = new EnsureStore();

            assertEquals(1, goal.methods().stream().filter(way -> way.applicable(ctx)).count(),
                    "walk-to and open-a-store are complements, which is what lets "
                            + "GatheringErrand promise the leg home can never be out of ways "
                            + "(chest already there: " + chestThere + ")");
        }
    }
}
