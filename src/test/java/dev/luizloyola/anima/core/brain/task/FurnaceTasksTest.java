package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SmeltLookup;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loading a furnace slot by slot, enough fuel for the load, and taking out what it made. */
class FurnaceTasksTest {

    private static final ItemSpec LOGS = ItemSpec.anyOf(Set.of("minecraft:oak_log"));
    private static final ItemSpec PLANKS = ItemSpec.anyOf(Set.of("minecraft:oak_planks"));
    private static final ItemSpec CHARCOAL = ItemSpec.anyOf(Set.of("minecraft:charcoal"));

    private final FakeContext ctx = new FakeContext();
    private final Pos furnace = new Pos(2, 64, 0);

    @BeforeEach
    void setUp() {
        ctx.furnaces.at(furnace);
        ctx.percepts.smelts.put("minecraft:oak_log", new SmeltLookup.Smelt("minecraft:charcoal", 200));
        ctx.percepts.burns.put("minecraft:oak_planks", 300);
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

    @Test
    void fuelIsCountedForTheWholeLoad() {
        assertEquals(11, LoadFurnace.fuelFor(ctx, LOGS, 16, PLANKS), "16 smelts of 200 ticks over 300 a plank");
        assertEquals(0, LoadFurnace.fuelFor(ctx, LOGS, 16, ItemSpec.anyOf(Set.of("minecraft:dirt"))),
                "nothing burns, so no load");
    }

    @Test
    void theFuelIsGotBeforeTheLoadItIsMadeOf() {
        List<Task> plan = new LoadFurnace(furnace, LOGS, 16, PLANKS).methods().get(0).decompose(ctx);

        ObtainItem first = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertEquals(PLANKS, first.spec());
        assertEquals(11, first.count());
        assertEquals(LOGS, assertInstanceOf(ObtainItem.class, plan.get(1)).spec());
        TendFurnace tend = assertInstanceOf(TendFurnace.class, plan.get(plan.size() - 1));
        assertEquals(16, tend.inputCount());
        assertEquals(11, tend.fuelCount());
    }

    @Test
    void aBodyFarOffWalksToTheFurnaceBeforeGettingAnything() {
        ctx.percepts.position = new Pos(200, 64, 0);

        List<Task> plan = new LoadFurnace(furnace, LOGS, 16, PLANKS).methods().get(0).decompose(ctx);

        GoTo there = assertInstanceOf(GoTo.class, plan.get(0));
        assertTrue(Math.abs(there.x() - furnace.x()) <= 1, "to the furnace, where its stores are");
        assertEquals(PLANKS, assertInstanceOf(ObtainItem.class, plan.get(1)).spec());
        assertEquals(LOGS, assertInstanceOf(ObtainItem.class, plan.get(2)).spec());
        assertInstanceOf(GoTo.class, plan.get(3));
        assertInstanceOf(TendFurnace.class, plan.get(4));
    }

    @Test
    void aFurnaceFoundGoneIsStruckFromTheRecordOnlyByABodyBesideIt() {
        Pos gone = new Pos(3, 64, 1);
        ctx.claim(dev.luizloyola.anima.core.craft.Furnace.POI, gone);
        TendFurnace tend = new TendFurnace(gone, null, LOGS, 16, PLANKS, 11);

        ctx.percepts.position = new Pos(200, 64, 0);
        assertEquals(TaskStatus.FAILED, run(tend, 10));
        assertEquals(1, ctx.knowledge.places().all(dev.luizloyola.anima.core.craft.Furnace.POI).stream()
                .filter(row -> row.at().equals(gone)).count(), "out of reach is not gone");

        ctx.percepts.position = new Pos(0, 64, 0);
        assertEquals(TaskStatus.FAILED, run(new TendFurnace(gone, null, LOGS, 16, PLANKS, 11), 10));
        assertTrue(ctx.knowledge.places().all(dev.luizloyola.anima.core.craft.Furnace.POI).stream()
                .noneMatch(row -> row.at().equals(gone)), "beside it and none there: struck");
    }

    @Test
    void theLoadGoesInTheInputAndTheFuelInTheFuel() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:oak_log", 16, 64));
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:oak_planks", 11, 64));

        assertEquals(TaskStatus.SUCCESS, run(new TendFurnace(furnace, null, LOGS, 16, PLANKS, 11), 200));

        FakeFurnaces.Box box = ctx.furnaces.at(furnace);
        assertEquals(ItemStack.of("minecraft:oak_log", 16, 64), box.input);
        assertEquals(ItemStack.of("minecraft:oak_planks", 11, 64), box.fuel);
        assertTrue(ctx.percepts.inventory.occupied().isEmpty(), "everything went in");
    }

    @Test
    void whatIsDoneIsTakenOutAndNothingElse() {
        FakeFurnaces.Box box = ctx.furnaces.at(furnace);
        box.output = ItemStack.of("minecraft:charcoal", 9, 64);
        box.input = ItemStack.of("minecraft:oak_log", 7, 64);

        assertEquals(TaskStatus.SUCCESS, run(new TendFurnace(furnace, CHARCOAL, null, 0, null, 0), 200));

        assertEquals(9, ctx.percepts.inventory.count("minecraft:charcoal"));
        assertEquals(7, box.input.count(), "the logs still smelting stay in");
    }

    @Test
    void noFurnaceInReachFails() {
        assertEquals(TaskStatus.FAILED,
                run(new TendFurnace(new Pos(40, 64, 0), CHARCOAL, null, 0, null, 0), 10));
    }

    @Test
    void anEmptyFurnaceToUnloadFails() {
        assertEquals(TaskStatus.FAILED, run(new TendFurnace(furnace, CHARCOAL, null, 0, null, 0), 200));
    }

    @Test
    void loadingThePartysFurnaceSetsAProcessGoingDueWhenTheLoadIsDone() {
        ctx.claim(dev.luizloyola.anima.core.craft.Furnace.POI, furnace);
        ctx.percepts.time = 100L;
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:oak_log", 16, 64));
        ctx.percepts.inventory.set(1, ItemStack.of("minecraft:oak_planks", 11, 64));

        run(new TendFurnace(furnace, null, LOGS, 16, PLANKS, 11), 200);

        var process = ctx.knowledge.places().process(dev.luizloyola.anima.core.craft.Furnace.POI, furnace)
                .orElseThrow();
        assertEquals("minecraft:charcoal", process.output());
        assertEquals(16, process.inputCount());
        assertEquals(ctx.knowledge.places().who(), process.starter(), "whoever loaded it comes back first");
        assertEquals(100L + 16 * 200, process.dueAt());
    }

    @Test
    void takingOutTheLastOfItEndsTheProcess() {
        ctx.claim(dev.luizloyola.anima.core.craft.Furnace.POI, furnace);
        ctx.knowledge.places().run(dev.luizloyola.anima.core.craft.Furnace.POI, furnace,
                new dev.luizloyola.anima.core.social.Process("smelt", "minecraft:oak_log", 16,
                        "minecraft:charcoal", ctx.knowledge.places().who(), 0L, 3200L));
        ctx.furnaces.at(furnace).output = ItemStack.of("minecraft:charcoal", 16, 64);

        run(new TendFurnace(furnace, CHARCOAL, null, 0, null, 0), 200);

        assertTrue(ctx.knowledge.places().process(dev.luizloyola.anima.core.craft.Furnace.POI, furnace).isEmpty());
    }

    @Test
    void takingOutWhatIsDoneWithMoreToSmeltPutsItOffAgain() {
        ctx.claim(dev.luizloyola.anima.core.craft.Furnace.POI, furnace);
        ctx.percepts.time = 1000L;
        ctx.knowledge.places().run(dev.luizloyola.anima.core.craft.Furnace.POI, furnace,
                new dev.luizloyola.anima.core.social.Process("smelt", "minecraft:oak_log", 16,
                        "minecraft:charcoal", ctx.knowledge.places().who(), 0L, 3200L));
        ctx.furnaces.at(furnace).output = ItemStack.of("minecraft:charcoal", 5, 64);
        ctx.furnaces.at(furnace).input = ItemStack.of("minecraft:oak_log", 11, 64);

        run(new TendFurnace(furnace, CHARCOAL, null, 0, null, 0), 200);

        var process = ctx.knowledge.places().process(dev.luizloyola.anima.core.craft.Furnace.POI, furnace)
                .orElseThrow();
        assertEquals(11, process.inputCount());
        assertEquals(0L, process.startedAt(), "the same process, going on");
        assertEquals(1000L + 11 * 200, process.dueAt(), "read off the furnace, not the old guess");
    }

    @Test
    void whatIsLeftWithNothingBurningIsRefuelled() {
        FakeFurnaces.Box box = ctx.furnaces.at(furnace);
        box.input = ItemStack.of("minecraft:oak_log", 6, 64);
        UnloadFurnace.Refuel refuel = new UnloadFurnace.Refuel(furnace, PLANKS);

        Method way = refuel.methods().get(0);
        assertTrue(way.applicable(ctx));
        ObtainItem fuel = assertInstanceOf(ObtainItem.class, way.decompose(ctx).get(0));
        assertEquals(4, fuel.count(), "six smelts of 200 over 300 a plank");

        box.lit = true;
        assertEquals(false, way.applicable(ctx), "something burns: nothing to do");
        box.lit = false;
        box.fuel = ItemStack.of("minecraft:oak_planks", 1, 64);
        assertEquals(false, way.applicable(ctx), "fuel waiting to burn is fuel");
    }

    @Test
    void aVisitThatFindsNothingDoneStillReadsTheFurnace() {
        ctx.claim(dev.luizloyola.anima.core.craft.Furnace.POI, furnace);
        ctx.percepts.time = 5000L;
        ctx.knowledge.places().run(dev.luizloyola.anima.core.craft.Furnace.POI, furnace,
                new dev.luizloyola.anima.core.social.Process("smelt", "minecraft:oak_log", 16,
                        "minecraft:charcoal", ctx.knowledge.places().who(), 0L, 3200L));
        ctx.furnaces.at(furnace).input = ItemStack.of("minecraft:oak_log", 16, 64);

        assertEquals(TaskStatus.FAILED, run(new TendFurnace(furnace, CHARCOAL, null, 0, null, 0), 200));

        assertEquals(5000L + 16 * 200, ctx.knowledge.places()
                .process(dev.luizloyola.anima.core.craft.Furnace.POI, furnace).orElseThrow().dueAt(),
                "put off again from what is there, not left due to be posted again at once");
    }
}
