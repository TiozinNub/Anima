package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SmeltLookup;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A hungry body with raw food and a campfire it knows cooks the food and eats it. */
class EatCookedTest {

    private final FakeContext ctx = new FakeContext();
    private final Pos fire = new Pos(10, 64, 0);

    @BeforeEach
    void setUp() {
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ctx.percepts.cooked("minecraft:beef", new FoodValue(8, 12.8F, false));
        ctx.percepts.food("minecraft:cooked_beef", new FoodValue(8, 12.8F, false));
        ctx.percepts.campfire.put("minecraft:beef", new SmeltLookup.Smelt("minecraft:cooked_beef", 600));
        ReadyFood.install(ctx.percepts.foods());
        ctx.percepts.metabolism.setFoodLevel(8);
    }

    @AfterEach
    void uninstall() {
        ReadyFood.install(null);
    }

    @Test
    void itIsAppendedAfterTheWaysAlreadySaved() {
        List<Method> methods = new SatisfyHunger().methods();
        assertInstanceOf(EatAnything.class, methods.get(4));
        assertInstanceOf(EatCooked.class, methods.get(5));
    }

    @Test
    void beefCarriedIsCookedAtTheFireAndEaten() {
        ctx.claim(Campfire.POI, fire);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 3, 64));
        EatCooked cook = new EatCooked();

        assertTrue(cook.applicable(ctx));
        assertEquals(10.0 + EatCooked.WAIT, cook.estimateCost(ctx), 1e-9);
        List<Task> plan = cook.decompose(ctx);
        CookAtCampfire there = assertInstanceOf(CookAtCampfire.class, plan.get(0));
        assertEquals(fire, there.at());
        assertEquals(RawFood.SPEC, there.raw());
        assertEquals(2, there.count(), "twelve short at eight a steak");
        assertInstanceOf(EatCarried.class, plan.get(1));
    }

    @Test
    void cookingIsWithinAHungryBodysMeansWhereEatingItRawIsNot() {
        ctx.claim(Campfire.POI, fire);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 3, 64));

        assertTrue(new EatCooked().estimateCost(ctx) < 60.0, "a hungry body spends 60");
        assertTrue(new EatLastResort().estimateCost(ctx) > 60.0);
        assertTrue(new EatCooked().estimateCost(ctx) > 15.0, "a peckish one waits");
    }

    @Test
    void noFireKnownNoCooking() {
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 3, 64));

        assertFalse(new EatCooked().applicable(ctx));
    }

    @Test
    void anEmptyBarEatsRawRatherThanWait() {
        ctx.claim(Campfire.POI, fire);
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 3, 64));
        ctx.percepts.metabolism.setFoodLevel(0);

        assertFalse(new EatCooked().applicable(ctx));
    }

    @Test
    void whatNoCampfireCooksIsNotAMeal() {
        ctx.claim(Campfire.POI, fire);
        ctx.percepts.food("minecraft:mystery", new FoodValue(1, 0.1F, false));
        ctx.percepts.cooked("minecraft:mystery", new FoodValue(5, 1.0F, false));
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:mystery", 3, 64));

        assertFalse(new EatCooked().applicable(ctx), "a furnace-only food: decision 10 keeps food out of furnaces");
    }

    @Test
    void beefSeenInAStoreIsFetchedOnTheWay() {
        ctx.claim(Campfire.POI, fire);
        Pos chest = new Pos(0, 64, 6);
        ctx.claim(Store.POI, chest);
        ctx.knowledge.sawInside(chest, List.of(ItemStack.of("minecraft:beef", 1, 64)), 0L,
                AgentKnowledge.maxPerKind(ctx.profile()));
        EatCooked cook = new EatCooked();

        assertTrue(cook.applicable(ctx));
        assertEquals(6.0 + Math.sqrt(100 + 36) + EatCooked.WAIT, cook.estimateCost(ctx), 1e-9);
        assertEquals(1, assertInstanceOf(CookAtCampfire.class, cook.decompose(ctx).get(0)).count(),
                "one is all there is");
    }

    @Test
    void theMealNamesNoItemSoNoHuntAnswersIt() {
        assertTrue(ItemSpec.literalIds(RawFood.SPEC).isEmpty());
        assertTrue(RawFood.SPEC.matches("minecraft:beef"));
        assertFalse(RawFood.SPEC.matches("minecraft:cooked_beef"));
    }
}
