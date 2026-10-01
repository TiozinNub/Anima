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
}
