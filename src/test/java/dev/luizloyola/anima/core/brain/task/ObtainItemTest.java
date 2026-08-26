package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The obtain goal's method roster. The producer BRIDGE: a literal (content-built) spec reaches
 * producers registered under an overlapping consumer spec, so a crafting ingredient can end in
 * a felled tree. The order is contract — a resumed plan re-finds its method BY INDEX, so nothing
 * may be inserted before an existing entry; every new way (like {@link TakeFromStore}) is
 * appended. {@link #aLiteralIngredientReachesTheConsumersProducerByContent()} pins the roster's
 * absolute shape below, which is what actually guards that against an insertion or a reorder.
 */
class ObtainItemTest {

    private static final ItemSpec LOGS = ItemSpec.register(
            new ItemSpec("obtain-test-logs", id -> id.endsWith("_log")));

    /** A recognizable stand-in for a consumer's felling choreography. */
    private static final class FellSomething implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return false;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of();
        }

        @Override
        public String describe() {
            return "fell something";
        }
    }

    @AfterEach
    void tearDown() {
        Producers.reset();
    }

    @Test
    void aLiteralIngredientReachesTheConsumersProducerByContent() {
        Producers.register(LOGS, wanted -> new FellSomething());
        // The ingredient shape: "any oak log", built from a recipe, never declared by a mod.
        ObtainItem ingredient = new ObtainItem(
                ItemSpec.anyOf(Set.of("minecraft:oak_log", "minecraft:oak_wood")), 1);
        List<Method> roster = ingredient.methods();
        // Load-bearing for saves: a resumed plan re-finds its method BY INDEX, so THIS is what
        // actually guards it — absolute SIZE plus the type at every absolute index, which an
        // insertion or reorder anywhere in the roster would break. The two tests pinning CraftFor
        // and TakeFromStore's relative order (CraftForTest, TakeFromStoreTest) are weaker: both
        // stay green if something is wrongly inserted ahead of CraftFor instead of appended.
        assertEquals(4, roster.size());
        assertInstanceOf(PickUpNearby.class, roster.get(0));
        assertInstanceOf(FellSomething.class, roster.get(1),
                "oak_log is a log the consumer knows how to produce — the chop is on the menu");
        assertInstanceOf(CraftFor.class, roster.get(2), "CraftFor joins right after the producers");
        assertInstanceOf(TakeFromStore.class, roster.get(3), "TakeFromStore joins after CraftFor");
    }

    @Test
    void anUnrelatedLiteralGetsNoBridge() {
        Producers.register(LOGS, wanted -> new FellSomething());
        ObtainItem stone = new ObtainItem(ItemSpec.anyOf(Set.of("minecraft:cobblestone")), 1);
        assertEquals(3, stone.methods().size(),
                "pick up + craft + take from store; nobody produces cobble");
    }

    @Test
    void aDeclaredSpecKeepsItsIdentityRosterUnchanged() {
        Producers.register(LOGS, wanted -> new FellSomething());
        List<Method> roster = new ObtainItem(LOGS, 16).methods();
        assertEquals(4, roster.size(), "identity producer once — the bridge never double-adds");
        assertInstanceOf(FellSomething.class, roster.get(1));
        assertTrue(roster.get(2) instanceof CraftFor);
        assertTrue(roster.get(3) instanceof TakeFromStore);
    }

    @Test
    void aProducerIsToldWhatTheGoalActuallyWants() {
        ItemSpec anyLog = ItemSpec.register(
                new ItemSpec("producer-test-logs", id -> id.endsWith("_log")));
        java.util.List<ItemSpec> asked = new java.util.ArrayList<>();
        Producers.register(anyLog, wanted -> {
            asked.add(wanted);
            return new FellSomething();
        });

        ItemSpec oakOnly = ItemSpec.anyOf(java.util.Set.of("minecraft:oak_log"));
        new ObtainItem(oakOnly, 4);

        assertEquals(java.util.List.of(oakOnly), asked,
                "registered for any log, asked for oak — the producer hears the narrow one");
    }
}
