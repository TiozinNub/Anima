package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SmeltLookup;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A cook at the campfires: every free slot filled, the drops picked up, more put on until done. */
class CampfireTasksTest {

    private static final ItemSpec BEEF = ItemSpec.anyOf(Set.of("minecraft:beef"));
    private static final String COOKED = "minecraft:cooked_beef";

    private final FakeContext ctx = new FakeContext();
    private final Pos fire = new Pos(2, 64, 0);

    @BeforeEach
    void setUp() {
        ctx.campfires.at(fire);
        ctx.percepts.campfire.put("minecraft:beef", new SmeltLookup.Smelt(COOKED, 600));
    }

    private TaskStatus run(PrimitiveTask task, int limit) {
        for (int tick = 0; tick < limit; tick++) {
            TaskStatus status = task.tick(ctx);
            if (status != TaskStatus.RUNNING) {
                return status;
            }
        }
        return TaskStatus.RUNNING;
    }

    /** Every fire's food cooked and caught by the body beside it, as a pickup would. */
    private void cookAndCatch() {
        int done = 0;
        for (FakeCampfires.Fire each : ctx.campfires.fires.values()) {
            done += each.cookAll().size();
        }
        ctx.percepts.inventory.add(ItemStack.of(COOKED, done, 64));
    }

    private int onFires() {
        int n = 0;
        for (FakeCampfires.Fire each : ctx.campfires.fires.values()) {
            for (ItemStack slot : each.slots) {
                n += slot.isEmpty() ? 0 : 1;
            }
        }
        return n;
    }

    @Test
    void theHearthIsTheFireThenTheKnownOnesBesideItNearestFirst() {
        for (Pos other : List.of(new Pos(2, 64, 3), new Pos(2, 64, 1), new Pos(2, 64, 7))) {
            ctx.claim(Campfire.POI, other);
        }

        assertEquals(List.of(fire, new Pos(2, 64, 1), new Pos(2, 64, 3)), Campfire.hearth(ctx, fire),
                "seven blocks off is too far to work from the same spot");
    }

    @Test
    void anUnlitFireDoesNotTakeALitOnesPlace() {
        Pos out = new Pos(2, 64, 1);
        ctx.claim(Campfire.POI, out);
        ctx.campfires.at(out).lit = false;
        for (Pos other : List.of(new Pos(2, 64, -1), new Pos(3, 64, 0), new Pos(2, 64, 2), new Pos(2, 64, -2))) {
            ctx.claim(Campfire.POI, other);
            ctx.campfires.at(other);
        }
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 20, 64));

        run(new TendCampfires(fire, BEEF, 20), 400);

        assertEquals(16, onFires(), "four lit fires of four");
        assertTrue(ctx.campfires.at(out).cookAll().isEmpty());
    }

    @Test
    void aFireSeenBesideItIsWorkedThoughNeverLearnt() {
        Pos seen = new Pos(3, 64, 1);
        ctx.percepts.blocks.set(seen.x(), seen.y(), seen.z(), Campfire.BLOCK);
        ctx.campfires.at(seen);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 8, 64));

        run(new TendCampfires(fire, BEEF, 8), 400);

        assertEquals(8, onFires(), "both fires full");
    }

    @Test
    void everyFreeSlotOfUpToFourFiresIsFilledThenRefilledUntilItIsAllCooked() {
        for (Pos other : List.of(new Pos(2, 64, 1), new Pos(2, 64, -1), new Pos(3, 64, 0), new Pos(2, 64, 2))) {
            ctx.claim(Campfire.POI, other);
            ctx.campfires.at(other);
        }
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 20, 64));
        TendCampfires cook = new TendCampfires(fire, BEEF, 20);

        assertEquals(TaskStatus.RUNNING, run(cook, 400), "waits for the round to cook");
        assertEquals(16, onFires(), "four fires of four");
        assertTrue(ctx.campfires.at(new Pos(2, 64, 2)).cookAll().isEmpty(), "a fifth fire is not worked");
        assertEquals(4, ctx.percepts.inventory.count("minecraft:beef"));

        cookAndCatch();
        assertEquals(TaskStatus.RUNNING, run(cook, 400));
        assertEquals(4, onFires(), "the rest put on");
        assertEquals(0, ctx.percepts.inventory.count("minecraft:beef"));

        cookAndCatch();
        assertEquals(TaskStatus.SUCCESS, run(cook, 10));
        assertEquals(20, ctx.percepts.inventory.count(COOKED));
    }

    @Test
    void onlyTheCountGoesOn() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 10, 64));

        run(new TendCampfires(fire, BEEF, 3), 400);

        assertEquals(3, onFires());
        assertEquals(7, ctx.percepts.inventory.count("minecraft:beef"));
    }

    @Test
    void anUnlitFireCooksNothing() {
        ctx.campfires.at(fire).lit = false;
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 4, 64));

        assertEquals(TaskStatus.FAILED, run(new TendCampfires(fire, BEEF, 4), 400));
        assertEquals(0, onFires());
    }

    @Test
    void whatNoCampfireCooksFails() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:wheat", 4, 64));

        assertEquals(TaskStatus.FAILED, run(new TendCampfires(fire, ItemSpec.anyOf(Set.of("minecraft:wheat")), 4), 5));
    }

    @Test
    void aDropThatRolledAwayIsWalkedToAndOneOnTheFireFromBesideIt() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));
        TendCampfires cook = new TendCampfires(fire, BEEF, 1);
        run(cook, 200);
        ctx.campfires.at(fire).cookAll();

        Pos rolled = new Pos(3, 64, 1);
        ctx.percepts.drops = List.of(new Drop(rolled, COOKED, Region.of(rolled)));
        ctx.mover.setState(MoveState.IDLE);
        cook.tick(ctx);
        assertEquals(List.of(3, 64, 1), List.of(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ));

        ctx.percepts.position = new Pos(-1, 64, 0);
        ctx.percepts.drops = List.of(new Drop(fire, COOKED, Region.of(fire)));
        run(cook, 25);
        assertTrue(!(ctx.mover.lastX == fire.x() && ctx.mover.lastZ == fire.z()), "never onto the fire");
    }

    @Test
    void aDropAboveAFireIsOnItAndEachSideIsTriedInTurn() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));
        TendCampfires cook = new TendCampfires(fire, BEEF, 1);
        run(cook, 200);
        ctx.campfires.at(fire).cookAll();
        Pos above = new Pos(fire.x(), fire.y() + 1, fire.z());
        ctx.percepts.drops = List.of(new Drop(above, COOKED, Region.of(above)));

        List<List<Integer>> stood = new java.util.ArrayList<>();
        int calls = ctx.mover.moveToCalls;
        for (int tick = 0; tick < 300; tick++) {
            cook.tick(ctx);
            if (ctx.mover.moveToCalls != calls) {
                calls = ctx.mover.moveToCalls;
                assertEquals(fire.y(), ctx.mover.lastY, "beside the fire, never up onto it");
                stood.add(List.of(ctx.mover.lastX, ctx.mover.lastZ));
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
            }
        }
        assertEquals(4, stood.size(), "every side once: " + stood);
        assertEquals(4, new java.util.HashSet<>(stood).size(), "none twice: " + stood);
    }

    @Test
    void aDropOneCellOffIsSteppedOntoNotWaitedFor() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));
        TendCampfires cook = new TendCampfires(fire, BEEF, 1);
        run(cook, 200);
        ctx.campfires.at(fire).cookAll();
        ctx.percepts.position = new Pos(1, 64, 1);
        Pos rim = new Pos(1, 64, 0);
        ctx.percepts.drops = List.of(new Drop(rim, COOKED, Region.of(rim)));

        cook.tick(ctx);

        assertEquals(List.of(1, 64, 0), List.of(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ),
                "a steak on a fire's rim lay a cell off, out of a body's pickup, for good (2026-10-01)");
    }

    @Test
    void nothingBackByTheEndOfARoundAndItsSlackGivesUp() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));
        TendCampfires cook = new TendCampfires(fire, BEEF, 1);

        assertEquals(TaskStatus.FAILED, run(cook, 600 + TendCampfires.SLACK + 50),
                "the beef never comes off");
    }

    @Test
    void aFireFoundGoneBesideItIsStruckFromTheRecord() {
        Pos gone = new Pos(1, 64, 1);
        ctx.claim(Campfire.POI, gone);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));

        assertEquals(TaskStatus.FAILED, run(new TendCampfires(gone, BEEF, 1), 10));
        assertTrue(ctx.knowledge.places().all(Campfire.POI).stream().noneMatch(row -> row.at().equals(gone)));
    }

    @Test
    void aCookAsksOnlyForWhatIsToHand() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 8, 64));
        CookAtCampfire cook = new CookAtCampfire(fire, BEEF, 12);

        assertEquals(8, cook.toHand(ctx), "a job for twelve failed for ever once four had gone (2026-10-01)");

        Pos chest = new Pos(0, 64, 4);
        ctx.claim(dev.luizloyola.anima.core.store.Store.POI, chest);
        ctx.knowledge.sawInside(chest, List.of(ItemStack.of("minecraft:beef", 10, 64)), 0L,
                dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.maxPerKind(ctx.profile()));
        assertEquals(12, cook.toHand(ctx), "never more than the count");

        ctx.percepts.inventory.set(0, ItemStack.EMPTY);
        ctx.knowledge.sawInside(chest, List.of(), 0L,
                dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.maxPerKind(ctx.profile()));
        assertEquals(12, cook.toHand(ctx), "nothing known: the fetch is left to fail");
    }

    @Test
    void aCookWalksThereGetsItAndStays() {
        ctx.percepts.position = new Pos(200, 64, 0);

        List<Task> plan = new CookAtCampfire(fire, BEEF, 8).methods().get(0).decompose(ctx);

        assertInstanceOf(GoTo.class, plan.get(0));
        ObtainItem get = assertInstanceOf(ObtainItem.class, plan.get(1));
        assertEquals(BEEF, get.spec());
        assertEquals(8, get.count());
        assertInstanceOf(GoTo.class, plan.get(2));
        assertEquals(8, assertInstanceOf(TendCampfires.class, plan.get(3)).count());
    }

    @Test
    void aDropThatRolledDownAHillIsWalkedToOnTheFloor() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));
        TendCampfires cook = new TendCampfires(fire, BEEF, 1);
        run(cook, 200);
        ctx.campfires.at(fire).cookAll();
        ctx.percepts.fallsAwayFrom(3, 4);

        Pos rolled = new Pos(3, 64, 1);
        ctx.percepts.drops = List.of(new Drop(rolled, COOKED, Region.of(rolled)));
        ctx.mover.setState(MoveState.IDLE);
        cook.tick(ctx);

        assertEquals(List.of(3, 60, 1), List.of(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ),
                "the legs lower a goal one cell at most (Luiz, 2026-10-02)");
    }
}
