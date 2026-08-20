package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A destination pinned onto 2b's goal: put the load in THAT yard, and only once carrying enough to
 * be worth the walk.
 */
class HaulToYardTest {

    private static final Pos HINT = new Pos(60, 64, 60);

    private static FakeContext packWithCargo(int slots) {
        FakeContext ctx = new FakeContext();
        Inventory pack = ctx.percepts.inventory();
        for (int slot = 0; slot < slots; slot++) {
            pack.set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
        return ctx;
    }

    /** A chest the body remembers AND the world backs — `standingAtOne` re-probes and disproves. */
    private static void remember(FakeContext ctx, Pos at) {
        ctx.knowledge.note(new PoiMemory(Store.POI, at, Region.of(at), 1, false, 0L), 64);
        ctx.percepts.blocks.set(at.x(), at.y(), at.z(), Store.BLOCK);
    }

    @Test
    void belowTheHaulLineThereIsNothingToDo() {
        FakeContext ctx = packWithCargo(4);

        assertTrue(new PutAwaySurplus(HINT, 12).satisfied(ctx),
                "four slots with a line of twelve: take the next tree, do not walk");
        assertFalse(new PutAwaySurplus().satisfied(ctx),
                "2b's flavour still hauls any cargo at all");
    }

    @Test
    void atTheLineItIsWorthTheWalk() {
        assertFalse(new PutAwaySurplus(HINT, 12).satisfied(packWithCargo(12)));
    }

    @Test
    void aYardOnlyCountsIfItIsNearTheHint() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, new Pos(2, 64, 0));           // a chest right here, nowhere near the yard

        assertFalse(new EnsureStore(HINT).methods().get(0).applicable(ctx),
                "the nearest chest is not the yard — walking to it would scatter the wood");
        assertTrue(new EnsureStore().methods().get(0).applicable(ctx),
                "2b's flavour is happy with any store");
    }

    @Test
    void aYardNearTheHintIsWalkedTo() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);
        remember(ctx, new Pos(62, 64, 60));         // two blocks from the hint

        assertTrue(new EnsureStore(HINT).methods().get(0).applicable(ctx));
    }

    @Test
    void withNoYardYetItBuildsOneNearTheHintRatherThanUnderfoot() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(0, 64, 0);

        List<Task> steps = new EnsureStore(HINT).methods().get(1).decompose(ctx);
        Pos spot = steps.stream().filter(step -> step instanceof FoundPlace)
                .map(step -> ((FoundPlace) step).anchor()).findFirst().orElseThrow();

        assertTrue(Store.distance(spot, HINT) <= 3.0,
                "the yard opens where it was asked for, give or take a block of ground");
        assertTrue(steps.stream().anyMatch(step -> step instanceof GoTo),
                "and the settler walks there first, since the hint is 60 blocks off");
    }

    @Test
    void beingAtSomeOtherChestDoesNotSatisfyAYardGoal() {
        FakeContext ctx = packWithCargo(12);
        ctx.percepts.position = new Pos(2, 64, 0);
        remember(ctx, new Pos(2, 64, 0));

        assertFalse(new EnsureStore(HINT).satisfied(ctx),
                "standing in a chest that is not the yard is not being at the yard");
        assertTrue(new EnsureStore().satisfied(ctx), "2b's flavour is satisfied anywhere");
    }
}
