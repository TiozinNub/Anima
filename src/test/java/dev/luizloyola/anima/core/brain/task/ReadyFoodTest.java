package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Ready food as a spec means exactly what {@link EatSelection} means by its ready tier. */
class ReadyFoodTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void uninstall() {
        ReadyFood.install(null);
    }

    @Test
    void itMatchesWhatAHungryBodyEatsWithoutAsking() {
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ctx.percepts.cooked("minecraft:beef", new FoodValue(8, 12.8F, false));
        ctx.percepts.food("minecraft:golden_apple", new FoodValue(4, 9.6F, true));
        ReadyFood.install(ctx.percepts.foods());

        assertTrue(ReadyFood.SPEC.matches("minecraft:bread"));
        assertFalse(ReadyFood.SPEC.matches("minecraft:beef"), "raw meat is an ingredient");
        assertFalse(ReadyFood.SPEC.matches("minecraft:golden_apple"), "a treat saved for starving");
        assertFalse(ReadyFood.SPEC.matches("minecraft:stick"));
    }

    @Test
    void withNoServerNothingIsFood() {
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        assertFalse(ReadyFood.SPEC.matches("minecraft:bread"));
    }
}
