package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.instinct.UnburdenInstinct;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A haul home: put the load in the depot's area, and only once carrying enough to be worth the
 * walk.
 */
class HaulToYardTest {

    private static final Pos HINT = new Pos(60, 64, 60);

    /** A depot of the hint's chunk alone, with the hint where a new chest would go. */
    private static void home(FakeContext ctx, Pos hint) {
        ctx.depot = Optional.of(new Depot.Site(hint,
                Set.of(ChunkKey.at(ChunkKey.OVERWORLD, hint.x(), hint.z()))));
    }

    private static FakeContext packWithCargo(int slots) {
        FakeContext ctx = new FakeContext();
        home(ctx, HINT);
        Inventory pack = ctx.percepts.inventory();
        for (int slot = 0; slot < slots; slot++) {
            pack.set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
        return ctx;
    }

    /** A chest the body remembers AND the world backs — `standingAtOne` re-probes and disproves. */
    private static void remember(FakeContext ctx, Pos at) {
        ctx.claim(Store.POI, at);
        ctx.percepts.blocks.set(at.x(), at.y(), at.z(), Store.BLOCK);
    }

    @Test
    void belowTheHaulLineThereIsNothingToDo() {
        FakeContext ctx = packWithCargo(4);

        assertTrue(new PutAwaySurplus(12).satisfied(ctx),
                "four stacks with a line of twelve: take the next tree, do not walk");
        assertFalse(new PutAwaySurplus(0).satisfied(ctx),
                "a line of zero hauls any cargo at all");
    }

    @Test
    void atTheLineItIsWorthTheWalk() {
        assertFalse(new PutAwaySurplus(12).satisfied(packWithCargo(12)));
    }

    @Test
    void aChestOnlyCountsIfItIsInTheArea() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, new Pos(2, 64, 0));           // a chest right here, outside the area

        assertFalse(new EnsureStore().methods().get(0).applicable(ctx),
                "the nearest chest is not home's — walking to it would scatter the wood");
    }

    @Test
    void aChestInTheAreaIsWalkedTo() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, new Pos(62, 64, 60));         // in the hint's chunk

        assertTrue(new EnsureStore().methods().get(0).applicable(ctx));
    }

    @Test
    void withNoChestYetItBuildsOneAtTheHintRatherThanUnderfoot() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);
        Pos spot = steps.stream().filter(step -> step instanceof FoundPlace)
                .map(step -> ((FoundPlace) step).anchor()).findFirst().orElseThrow();

        assertTrue(Store.distance(spot, HINT) <= 3.0,
                "the store opens at the hint, give or take a block of ground");
        assertTrue(steps.stream().anyMatch(step -> step instanceof GoTo),
                "and the settler walks there first, since the hint is 60 blocks off");
    }

    @Test
    void aHintInTheAirLandsOnTheGroundUnderIt() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);
        // FakeProbe's world is flat ground at y 63, so y 64 is the standable cell here. An
        // operator pointing from a hilltop names something ten blocks up.
        Pos inTheAir = new Pos(60, 74, 60);
        home(ctx, inTheAir);

        List<Task> steps = new EnsureStore().methods().get(1).decompose(ctx);
        Pos spot = steps.stream().filter(step -> step instanceof FoundPlace)
                .map(step -> ((FoundPlace) step).anchor()).findFirst().orElseThrow();

        assertEquals(64, spot.y(),
                "the chest goes on the floor beneath the spot, not hanging where it was asked for");
        assertEquals(60, spot.x());
        assertEquals(60, spot.z());
    }

    @Test
    void beingAtSomeOtherChestDoesNotSatisfyAHaulHome() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(2, 64, 0);
        remember(ctx, new Pos(2, 64, 0));

        assertFalse(new EnsureStore().satisfied(ctx),
                "standing at a chest outside the area is not being home");
    }

    /** One tree in a mixed wood, as the pack held it on 2026-09-27: five kinds, sixteen items. */
    private static FakeContext packAfterOneTree() {
        FakeContext ctx = new FakeContext();
        Inventory pack = ctx.percepts.inventory();
        pack.set(0, ItemStack.of("minecraft:oak_log", 5, 64));
        pack.set(1, ItemStack.of("minecraft:birch_log", 4, 64));
        pack.set(2, ItemStack.of("minecraft:oak_sapling", 2, 64));
        pack.set(3, ItemStack.of("minecraft:stick", 3, 64));
        pack.set(4, ItemStack.of("minecraft:leaf_litter", 2, 64));
        return ctx;
    }

    @Test
    void aSlotIsNotAStack() {
        assertTrue(new PutAwaySurplus(3).satisfied(packAfterOneTree()),
                "five kinds of one tree are a quarter of a stack, not five slots over a line of three");
    }

    @Test
    void whenTheJobHasNothingLeftEverythingGoes() {
        assertTrue(new PutAwaySurplus(3, () -> true).satisfied(packAfterOneTree()),
                "mid-job, a tree's worth waits for the line");
        assertFalse(new PutAwaySurplus(3, () -> false).satisfied(packAfterOneTree()),
                "the last tree: take it all home");
        assertTrue(new PutAwaySurplus(3, () -> false).satisfied(new FakeContext()),
                "and an empty pack has nothing to take");
    }

    @Test
    void aFarHomeIsNotPricedOutOfAJob() {
        FakeContext ctx = packWithCargo(3);
        ctx.percepts.position = new Pos(-140, 64, 0);   // 200 blocks off, past any job's budget
        remember(ctx, new Pos(62, 64, 60));
        double budget = dev.luizloyola.anima.core.brain.WorkToleranceCurve.tolerance(1.0);

        assertTrue(new PutAwaySurplus(3).methods().get(0).estimateCost(ctx) <= budget,
                "the walk home is the job, not a choice within it");
        assertTrue(new EnsureStore().methods().get(0).estimateCost(ctx) <= budget);
    }

    /** {@code empty} storage slots free, every other one holding a single item. */
    private static FakeContext packOfOnes(int empty) {
        FakeContext ctx = new FakeContext();
        Inventory pack = ctx.percepts.inventory();
        for (int slot = 0; slot < Inventory.ARMOR_START - empty; slot++) {
            pack.set(slot, ItemStack.of("minecraft:kind_" + slot, 1, 64));
        }
        return ctx;
    }

    @Test
    void aPackRunningOutOfRoomGoesWhateverTheLoad() {
        int roomLine = new FakeContext().profile.i(ProfileAspect.UNBURDEN_SLACK_SLOTS)
                + PutAwaySurplus.ROOM_MARGIN;
        assertTrue(new PutAwaySurplus(3).satisfied(packOfOnes(roomLine + 1)));
        assertFalse(new PutAwaySurplus(3).satisfied(packOfOnes(roomLine)),
                "a pack of odds and ends is laden by room, not by weight");
    }

    @Test
    void wheneverUnburdenWouldBidTheJobsHaulIsAlreadyDue() {
        // Unburden and a job's haul both go home; the job's has to have gone first.
        for (int empty = 0; empty <= Inventory.ARMOR_START; empty++) {
            FakeContext ctx = packOfOnes(empty);
            home(ctx, new Pos(0, 64, 0));
            if (new UnburdenInstinct().pressure(ctx) > 0.0) {
                assertFalse(new PutAwaySurplus(3).satisfied(ctx), "empty=" + empty);
            }
        }
    }
}
