package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Optional;
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
        Gate.install(Gate.OPEN);
    }

    /** A context whose gate refuses shears and cobblestone — a Wood Age body, near enough. */
    private static FakeContext woodAge() {
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return itemId.equals("minecraft:shears") || itemId.equals("minecraft:cobblestone")
                        ? Optional.of("not yet") : Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return Optional.empty();
            }
        });
        FakeContext ctx = new FakeContext();
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());
        return ctx;
    }

    @Test
    void anObtainTheGateRefusesFailsBeforeTryingAnyWay() {
        FakeContext ctx = woodAge();
        TaskExecutor executor = new TaskExecutor();
        executor.run(new ObtainItem(ItemSpec.anyOf(Set.of("minecraft:shears")), 1), ctx);
        executor.tick(ctx);
        assertEquals(Optional.of("obtain shears x1: may not seek it"), executor.failureReason());
    }

    @Test
    void anIngredientOfAnAllowedCraftIsNotAskedAgain() {
        FakeContext ctx = woodAge();
        ItemSpec cobble = ItemSpec.anyOf(Set.of("minecraft:cobblestone"));
        assertTrue(new ObtainItem(cobble, 8).refusal(ctx).isPresent(), "sought for itself: refused");
        assertTrue(new ObtainItem(cobble, 8, Set.of("minecraft:furnace")).refusal(ctx).isEmpty(),
                "sought for a furnace the gate let through: the recipe already asked");
    }

    @Test
    void aLiteralIngredientReachesTheConsumersProducerByContent() {
        Producers.register(LOGS, LOGS::matches, wanted -> new FellSomething());
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
        Producers.register(LOGS, LOGS::matches, wanted -> new FellSomething());
        ObtainItem stone = new ObtainItem(ItemSpec.anyOf(Set.of("minecraft:cobblestone")), 1);
        assertEquals(3, stone.methods().size(),
                "pick up + craft + take from store; nobody produces cobble");
    }

    @Test
    void aDeclaredSpecKeepsItsIdentityRosterUnchanged() {
        Producers.register(LOGS, LOGS::matches, wanted -> new FellSomething());
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
        Producers.register(anyLog, anyLog::matches, wanted -> {
            asked.add(wanted);
            return new FellSomething();
        });

        ItemSpec oakOnly = ItemSpec.anyOf(java.util.Set.of("minecraft:oak_log"));
        new ObtainItem(oakOnly, 4);

        assertEquals(java.util.List.of(oakOnly), asked,
                "registered for any log, asked for oak — the producer hears the narrow one");
    }
}
