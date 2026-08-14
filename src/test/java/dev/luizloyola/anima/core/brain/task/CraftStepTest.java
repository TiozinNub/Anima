package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The exchange primitive: a worked pause per craft, then bill-out-output-in atomically in one
 * tick, re-verified at both edges because the pack is live state. All against the real core
 * {@link dev.luizloyola.anima.core.inv.Inventory} in the fake context.
 */
class CraftStepTest {

    private static CraftRecipe planksFromLog() {
        return new CraftRecipe("minecraft:oak_planks",
                ItemStack.of("minecraft:oak_planks", 4, 64),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_log"), 1)), false);
    }

    private final FakeContext ctx = new FakeContext();

    /** Ticks until the running step returns a terminal status, bounded so a bug cannot hang. */
    private TaskStatus runOut(CraftStep step, int maxTicks) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int tick = 0; tick < maxTicks && status == TaskStatus.RUNNING; tick++) {
            status = step.tick(ctx);
        }
        return status;
    }

    @Test
    void oneCraftExchangesTheBillForTheOutputAfterThePause() {
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 3, 64));
        CraftStep step = new CraftStep(planksFromLog(), 1);
        assertEquals(TaskStatus.SUCCESS, runOut(step, CraftStep.CRAFT_TICKS + 4));
        assertEquals(2, ctx.percepts.inventory.count("minecraft:oak_log"), "one log paid");
        assertEquals(4, ctx.percepts.inventory.count("minecraft:oak_planks"), "four planks made");
    }

    @Test
    void severalCraftsRunBackToBackAndCountTheirProgress() {
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 2, 64));
        CraftStep step = new CraftStep(planksFromLog(), 2);
        assertEquals(TaskStatus.SUCCESS, runOut(step, 2 * CraftStep.CRAFT_TICKS + 8));
        assertEquals(0, ctx.percepts.inventory.count("minecraft:oak_log"));
        assertEquals(8, ctx.percepts.inventory.count("minecraft:oak_planks"));
        assertEquals(2, step.done());
    }

    @Test
    void missingMaterialsFailBeforeAnythingIsConsumed() {
        CraftStep step = new CraftStep(planksFromLog(), 1);
        assertEquals(TaskStatus.FAILED, step.tick(ctx), "empty pack: no bill, no exchange");
        assertTrue(ctx.percepts.inventory.isEmpty(), "nothing was taken for a craft that never ran");
    }

    @Test
    void materialsVanishingMidPauseFailTheCraftHonestly() {
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        CraftStep step = new CraftStep(planksFromLog(), 1);
        assertEquals(TaskStatus.RUNNING, step.tick(ctx), "the pause began against a full bill");
        ctx.percepts.inventory.remove("minecraft:oak_log", 1); // eaten, dropped, burned…
        assertEquals(TaskStatus.FAILED, runOut(step, CraftStep.CRAFT_TICKS + 4));
        assertEquals(0, ctx.percepts.inventory.count("minecraft:oak_planks"));
    }

    @Test
    void aFullPackWithNoRoomForTheOutputRefusesBeforeConsuming() {
        // Every storage slot holds an unstackable stranger: the four planks have nowhere to go.
        for (int slot = 0; slot < dev.luizloyola.anima.core.inv.Inventory.ARMOR_START; slot++) {
            ctx.percepts.inventory.set(slot, ItemStack.of("minecraft:iron_sword", 1, 1));
        }
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:oak_log", 1, 64));
        // The bill is present (slot 0), but consuming it would still leave the output homeless
        // this tick — the exchange refuses whole rather than vanishing the log.
        CraftStep step = new CraftStep(planksFromLog(), 1);
        assertEquals(TaskStatus.FAILED, step.tick(ctx));
        assertEquals(1, ctx.percepts.inventory.count("minecraft:oak_log"), "the log survived");
    }

    @Test
    void aReloadMidPauseResumesTheTickThatWasComing() {
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        CraftStep before = new CraftStep(planksFromLog(), 1);
        before.tick(ctx); // pause begun
        before.tick(ctx); // one tick worked
        CraftStep after = new CraftStep(planksFromLog(), 1)
                .resume(before.done(), before.workTicks());
        assertEquals(TaskStatus.SUCCESS, runOut(after, CraftStep.CRAFT_TICKS + 4),
                "the restored step finishes the same craft without restarting the pause");
        assertEquals(4, ctx.percepts.inventory.count("minecraft:oak_planks"));
    }
}
