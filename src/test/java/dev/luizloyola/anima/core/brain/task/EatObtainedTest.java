package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A hungry body with nothing in hand or in store gets ready food the ways a consumer taught it —
 * or picks some up — and eats it, priced by what getting it costs.
 */
class EatObtainedTest {

    private final FakeContext ctx = new FakeContext();

    /** A consumer's producer of ready food: a patch {@code cost} away, or none. */
    private record Patch(boolean there, double cost) implements Method {
        @Override
        public boolean applicable(BrainContext c) {
            return there;
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
            return "forage";
        }
    }

    @BeforeEach
    void setUp() {
        ctx.percepts.food("minecraft:sweet_berries", new FoodValue(2, 0.4F, false));
        ReadyFood.install(ctx.percepts.foods());
        ctx.percepts.metabolism.setFoodLevel(8);
    }

    @AfterEach
    void tearDown() {
        ReadyFood.install(null);
        Producers.reset();
    }

    @Test
    void itIsAppendedAfterTheWaysAlreadySaved() {
        List<Method> methods = new SatisfyHunger().methods();
        assertEquals(7, methods.size());
        assertInstanceOf(EatFromStore.class, methods.get(2));
        assertInstanceOf(EatObtained.class, methods.get(3));
        assertInstanceOf(EatAnything.class, methods.get(4));
    }

    @Test
    void withNoWayToGetFoodThereIsNothingToDo() {
        assertFalse(new EatObtained().applicable(ctx));
    }

    @Test
    void aProducerOfReadyFoodIsAWayAndItsPriceIsTheMeals() {
        Producers.register(ReadyFood.SPEC, ReadyFood.SPEC::matches, wanted -> new Patch(true, 27.0));

        assertTrue(new EatObtained().applicable(ctx));
        assertEquals(27.0, new EatObtained().estimateCost(ctx), 1e-9);
    }

    @Test
    void theCheapestWayPricesIt() {
        Producers.register(ReadyFood.SPEC, ReadyFood.SPEC::matches, wanted -> new Patch(true, 27.0));
        Pos at = new Pos(0, 64, 9);
        ctx.percepts.drops = List.of(new Drop(at, "minecraft:sweet_berries", Region.of(at)));

        assertEquals(9.0, new EatObtained().estimateCost(ctx), 1e-9,
                "berries on the ground nine blocks off beat a patch twenty-seven off");
    }

    @Test
    void aFullBarGoesLookingForNothing() {
        Producers.register(ReadyFood.SPEC, ReadyFood.SPEC::matches, wanted -> new Patch(true, 5.0));
        ctx.percepts.metabolism.setFoodLevel(20);
        assertFalse(new EatObtained().applicable(ctx));
    }

    @Test
    void itGetsFoodThenEatsIt() {
        Producers.register(ReadyFood.SPEC, ReadyFood.SPEC::matches, wanted -> new Patch(true, 5.0));
        List<Task> plan = new EatObtained().decompose(ctx);

        ObtainItem obtain = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertEquals(ReadyFood.SPEC, obtain.spec());
        assertEquals(ObtainItem.Sources.NOT_STORES, obtain.sources(), "stores are EatFromStore's way");
        assertInstanceOf(EatCarried.class, plan.get(1));
    }
}
