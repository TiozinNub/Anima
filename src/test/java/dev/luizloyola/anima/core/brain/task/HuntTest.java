package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Quarry;
import dev.luizloyola.anima.core.brain.sense.Yields;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Hunt}'s three plans. The test body fears polar bears (the default 1.0) and not cows
 * ({@code TestDanger}); a cow drops beef and leather, a polar bear cod.
 */
class HuntTest {

    private final FakeContext ctx = new FakeContext();

    static void installYields() {
        Map<String, Set<String>> drops = Map.of(
                "cow", Set.of("minecraft:beef", "minecraft:leather"),
                "polar_bear", Set.of("minecraft:cod"));
        Yields.install(new Yields.Lookup() {
            @Override
            public Set<String> of(String species) {
                return drops.getOrDefault(species, Set.of());
            }

            @Override
            public Set<String> species() {
                return drops.keySet();
            }
        });
    }

    @BeforeEach
    void foods() {
        installYields();
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ctx.percepts.food("minecraft:cod", new FoodValue(2, 0.4F, false));
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        ReadyFood.install(ctx.percepts.foods());
    }

    @AfterEach
    void uninstall() {
        Yields.install(null);
        ReadyFood.install(null);
    }

    private Hunt hunt() {
        return new Hunt(ReadyFood.SPEC, null);
    }

    @Test
    void aCowInViewIsFoughtAndWhatItDropsPickedUp() {
        Being cow = FakePercepts.animalAt(BeingId.of(UUID.randomUUID()), "cow", new Pos(6, 64, 0), 6.0);
        ctx.percepts.beings = List.of(cow);

        assertTrue(hunt().applicable(ctx));
        assertEquals(6.0 + Hunt.WORK, hunt().estimateCost(ctx), 1e-9);
        List<Task> plan = hunt().decompose(ctx);
        assertEquals(cow.id(), assertInstanceOf(Fight.class, plan.get(0)).target());
        GatherNearbyDrops pickup = assertInstanceOf(GatherNearbyDrops.class,
                assertInstanceOf(Try.class, plan.get(1)).attempt());
        assertTrue(pickup.spec().matches("minecraft:leather"), "everything the cow dropped, not only the beef");
    }

    @Test
    void theLonerChosenIsForgottenSoItsCarcassIsNotScoutedFor() {
        BeingId id = BeingId.of(UUID.randomUUID());
        Pos at = new Pos(6, 64, 0);
        ctx.percepts.beings = List.of(FakePercepts.animalAt(id, "cow", at, 6.0));
        ctx.knowledge.note(new PoiMemory(PoiKind.HERD, "cow", id.value(), at, Region.of(at), 1, false, 0L), 64);

        hunt().decompose(ctx);

        assertTrue(ctx.knowledge.all(PoiKind.HERD).isEmpty());
    }

    @Test
    void theNearestHeadOfAHerdIsTheOneHunted() {
        BeingId far = BeingId.of(UUID.randomUUID());
        BeingId near = BeingId.of(UUID.randomUUID());
        BeingId middle = BeingId.of(UUID.randomUUID());
        ctx.percepts.beings = List.of(FakePercepts.herdOf("cow", new Pos(7, 64, 0), 7.0,
                List.of(far, near, middle)));
        ctx.percepts.hidden.put(far, FakePercepts.animalAt(far, "cow", new Pos(9, 64, 0), 9.0));
        ctx.percepts.hidden.put(near, FakePercepts.animalAt(near, "cow", new Pos(5, 64, 0), 5.0));
        ctx.percepts.hidden.put(middle, FakePercepts.animalAt(middle, "cow", new Pos(7, 64, 0), 7.0));

        assertEquals(near, assertInstanceOf(Fight.class, hunt().decompose(ctx).get(0)).target());
    }

    @Test
    void aCalfOrSomebodysCowIsLeftAlone() {
        BeingId calf = BeingId.of(UUID.randomUUID());
        BeingId pet = BeingId.of(UUID.randomUUID());
        ctx.percepts.beings = List.of(
                FakePercepts.animalAt(calf, "cow", new Pos(3, 64, 0), 3.0),
                FakePercepts.animalAt(pet, "cow", new Pos(4, 64, 0), 4.0));
        ctx.percepts.quarries.put(calf, new Quarry(true, false));
        ctx.percepts.quarries.put(pet, new Quarry(false, true));

        assertEquals(List.of(), hunt().decompose(ctx).stream().filter(Fight.class::isInstance).toList());
        assertEquals(Hunt.SEARCH_COST, hunt().estimateCost(ctx), 1e-9, "neither counts; go and look");
    }

    @Test
    void aFearedAnimalIsNeverPreyWhateverItDrops() {
        ctx.percepts.beings = List.of(FakePercepts.animalAt(BeingId.of(UUID.randomUUID()), "polar_bear",
                new Pos(5, 64, 0), 5.0));
        Hunt forFish = new Hunt(ItemSpec.anyOf(Set.of("minecraft:cod")), null);

        assertFalse(forFish.applicable(ctx), "the only thing that drops cod is feared: nothing to hunt, nor to look for");
    }

    @Test
    void nothingThatDropsWhatIsWantedIsNothingToLookFor() {
        assertFalse(new Hunt(ItemSpec.anyOf(Set.of("minecraft:bread")), null).applicable(ctx));
    }

    @Test
    void aRememberedHerdIsScouted() {
        Pos anchor = new Pos(30, 64, 0);
        ctx.knowledge.note(new PoiMemory(PoiKind.HERD, "cow", anchor,
                new Region(new Pos(26, 62, -4), new Pos(34, 66, 4)), 5, false, 0L), 64);

        assertEquals(30.0 + Hunt.WORK, hunt().estimateCost(ctx), 1e-9);
        Scout scout = assertInstanceOf(Scout.class, hunt().decompose(ctx).get(0));
        assertEquals(anchor, scout.anchor());
        assertEquals(10, scout.radius(), "2.5 times the herd's half-width of 4");
    }

    @Test
    void withNothingKnownItGoesLooking() {
        assertTrue(hunt().applicable(ctx));
        assertEquals(Hunt.SEARCH_COST, hunt().estimateCost(ctx), 1e-9);
        assertInstanceOf(SeekPrey.class, hunt().decompose(ctx).get(0));
    }

    @Test
    void groundAlreadySearchedIsNotSearchedAgainForAWhile() {
        ctx.knowledge.avoid(PoiKind.HERD, SeekPrey.restKey(ctx.percepts.position()), 100L);
        assertFalse(hunt().applicable(ctx));
        ctx.percepts.time = 100L;
        assertTrue(hunt().applicable(ctx));
    }
}
