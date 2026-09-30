package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Raw food is food, and eaten only by a starving body (directions spec, decision 16): a hunt is a
 * way to get food, never a way to get a meal for a body that is only hungry.
 */
class EatAnythingTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    /** A hunt: produces raw beef, {@code cost} away. */
    private record Hunting(double cost) implements Method {
        @Override
        public boolean applicable(BrainContext c) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext c) {
            return cost;
        }

        @Override
        public List<Task> decompose(BrainContext c) {
            return List.of();
        }

        @Override
        public String describe() {
            return "hunt";
        }
    }

    @BeforeEach
    void setUp() {
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ctx.percepts.cooked("minecraft:beef", new FoodValue(8, 12.8F, false));
        ctx.percepts.food("minecraft:sweet_berries", new FoodValue(2, 0.4F, false));
        ReadyFood.install(ctx.percepts.foods());
    }

    @AfterEach
    void tearDown() {
        ReadyFood.install(null);
        Producers.reset();
    }

    @Test
    void rawMeatIsFoodButNotReady() {
        assertTrue(Food.SPEC.matches("minecraft:beef"));
        assertTrue(Food.SPEC.matches("minecraft:sweet_berries"));
        assertFalse(ReadyFood.SPEC.matches("minecraft:beef"), "raw: eaten only when starving");
    }

    @Test
    void aHungryBodyDoesNotHuntForAMeal() {
        Producers.register(Food.SPEC, wanted -> new Hunting(10.0));
        ctx.percepts.metabolism.setFoodLevel(8);
        ctx.costTolerance = 60.0;

        executor.run(new SatisfyHunger(), ctx);
        executor.tick(ctx);

        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus(),
                "10 of walk and 80 of last resort is over a hungry body's 60");
        assertEquals(90.0, new EatAnything().estimateCost(ctx), 1e-9);
    }

    @Test
    void aStarvingBodyGetsAnyFoodAndEatsIt() {
        Producers.register(Food.SPEC, wanted -> new Hunting(10.0));
        ctx.percepts.metabolism.setFoodLevel(2);

        List<Task> plan = new EatAnything().decompose(ctx);

        ObtainItem obtain = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertEquals(Food.SPEC, obtain.spec());
        assertEquals(ObtainItem.Sources.ANY, obtain.sources(), "raw meat in a chest is food too");
        assertInstanceOf(EatCarried.class, plan.get(1));
    }

    @Test
    void readyFoodToBeHadStillWinsWhenStarving() {
        Producers.register(Food.SPEC, wanted -> new Hunting(5.0));
        Producers.register(ReadyFood.SPEC, wanted -> new Hunting(30.0));
        ctx.percepts.metabolism.setFoodLevel(2);

        assertTrue(new EatObtained().estimateCost(ctx) < new EatAnything().estimateCost(ctx),
                "berries thirty blocks off before a cow five off");
    }

    @Test
    void whatWasFetchedIsEatenRawOnlyByAStarvingBody() {
        ctx.percepts.inventory.set(3, ItemStack.of("minecraft:beef", 1, 64));
        ctx.percepts.metabolism.setFoodLevel(8);
        ctx.costTolerance = 60.0;
        executor.run(new EatCarried(), ctx);
        executor.tick(ctx);
        assertEquals(0, ctx.consumer.beginCalls, "hungry: the beef is kept");

        ctx.costTolerance = Double.POSITIVE_INFINITY;
        executor.run(new EatCarried(), ctx);
        executor.tick(ctx);
        assertEquals(3, ctx.consumer.lastSlot, "starving: eaten raw");
    }
}
