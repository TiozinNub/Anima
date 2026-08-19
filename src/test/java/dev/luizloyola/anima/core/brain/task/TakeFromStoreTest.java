package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Taking a thing out of a store a party already filled: applicable only against a body's OWN
 * {@code insideOf} sighting (a remembered place says nothing about contents), priced at distance
 * plus staleness, and last in {@link ObtainItem}'s method list so a saved plan's earlier indices
 * never move.
 */
class TakeFromStoreTest {

    private static final ItemSpec LOGS = ItemSpec.register(
            new ItemSpec("take-from-store-test-logs", id -> id.endsWith("_log")));

    /** A {@link FakeContext} that knows a store at {@code at}, last seen holding nine logs. */
    private static FakeContext ctxKnowing(Pos at, long seenTick, long now) {
        FakeContext ctx = new FakeContext();
        ctx.knowledge.note(new PoiMemory(Store.POI, at, Region.of(at), 1, false, seenTick),
                AgentKnowledge.maxPerKind(ctx.profile()));
        ctx.knowledge.sawInside(at, List.of(ItemStack.of("minecraft:oak_log", 9, 64)), seenTick,
                AgentKnowledge.maxPerKind(ctx.profile()));
        ctx.percepts.time = now;
        return ctx;
    }

    @Test
    void takeFromStoreIsTheLastMethodSoSavedPlansKeepTheirIndices() {
        List<Method> methods = new ObtainItem(LOGS, 4, java.util.Set.of()).methods();
        assertTrue(methods.get(methods.size() - 1) instanceof TakeFromStore,
                "a saved plan resumes its method by INDEX; anything inserted above CraftFor "
                        + "re-points every plan already on disk");
    }

    @Test
    void itIsApplicableOnlyWhenAKnownStoreIsBelievedToHoldTheThing() {
        FakeContext ctx = new FakeContext();
        TakeFromStore method = new TakeFromStore(LOGS, 4);
        assertFalse(method.applicable(ctx), "no store known, nothing to take from");

        Pos at = new Pos(6, 64, 0);
        ctx.knowledge.note(new PoiMemory(Store.POI, at, Region.of(at), 1, false, 0L), 64);
        assertFalse(method.applicable(ctx), "a store nobody has opened says nothing about its contents");

        ctx.knowledge.sawInside(at, List.of(ItemStack.of("minecraft:oak_log", 9, 64)), 0L,
                AgentKnowledge.maxPerKind(ctx.profile()));
        assertTrue(method.applicable(ctx));
    }

    @Test
    void aStaleBeliefCostsMoreThanAFreshOneAtTheSameDistance() {
        FakeContext fresh = ctxKnowing(new Pos(6, 64, 0), 1000L, 1000L);
        FakeContext stale = ctxKnowing(new Pos(6, 64, 0), 0L, 1000L);
        assertTrue(new TakeFromStore(LOGS, 4).estimateCost(stale)
                        > new TakeFromStore(LOGS, 4).estimateCost(fresh),
                "distance alone would send a settler to a chest emptied an hour ago");
    }

    @Test
    void itWalksToTheStoreAndTakesFromIt() {
        FakeContext ctx = ctxKnowing(new Pos(6, 64, 0), 0L, 0L);
        List<Task> plan = new TakeFromStore(LOGS, 4).decompose(ctx);
        assertEquals(2, plan.size());
        assertTrue(plan.get(0) instanceof GoTo);
        assertTrue(plan.get(1) instanceof TakeItems);
    }
}
