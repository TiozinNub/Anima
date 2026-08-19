package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 * {@code insideOf} sighting (a remembered place says nothing about contents), gated shut again
 * once that sighting no longer covers the spec, priced at distance plus staleness over every
 * known store, and appended after {@link CraftFor} in {@link ObtainItem}'s method list — new
 * ways are always appended, never inserted, so a saved plan's earlier indices hold.
 */
class TakeFromStoreTest {

    private static final ItemSpec LOGS = ItemSpec.register(
            new ItemSpec("take-from-store-test-logs", id -> id.endsWith("_log")));

    /** Records a store belief at {@code at}, last opened at {@code seenTick}, holding nine logs. */
    private static void seeStoreAt(FakeContext ctx, Pos at, long seenTick) {
        ctx.knowledge.note(new PoiMemory(Store.POI, at, Region.of(at), 1, false, seenTick),
                AgentKnowledge.maxPerKind(ctx.profile()));
        ctx.knowledge.sawInside(at, List.of(ItemStack.of("minecraft:oak_log", 9, 64)), seenTick,
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    /** A fresh {@link FakeContext} that already knows one such store. */
    private static FakeContext ctxKnowing(Pos at, long seenTick, long now) {
        FakeContext ctx = new FakeContext();
        seeStoreAt(ctx, at, seenTick);
        ctx.percepts.time = now;
        return ctx;
    }

    @Test
    void takeFromStoreIsAppendedLast() {
        // Only pins TakeFromStore's OWN relative position — last among ObtainItem's ways. It
        // would stay green even if a defect inserted a new way ahead of CraftFor instead of
        // appending one, since TakeFromStore would still land last either way. The absolute-index
        // guarantee a saved plan actually depends on is pinned in ObtainItemTest's
        // aLiteralIngredientReachesTheConsumersProducerByContent, not here.
        List<Method> methods = new ObtainItem(LOGS, 4, java.util.Set.of()).methods();
        assertTrue(methods.get(methods.size() - 1) instanceof TakeFromStore);
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
    void itIsNotApplicableWhenTheSightingDoesNotCoverTheSpec() {
        FakeContext ctx = new FakeContext();
        Pos at = new Pos(6, 64, 0);
        ctx.knowledge.note(new PoiMemory(Store.POI, at, Region.of(at), 1, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
        TakeFromStore method = new TakeFromStore(LOGS, 4);

        ctx.knowledge.sawInside(at, List.of(ItemStack.of("minecraft:cobblestone", 9, 64)), 0L,
                AgentKnowledge.maxPerKind(ctx.profile()));
        assertFalse(method.applicable(ctx), "a chest of cobble is no way to obtain logs");

        // What TakeItems writes back after the last matching stack comes out — the loop must
        // close here rather than keep sending a settler back to a chest it just emptied.
        ctx.knowledge.sawInside(at, List.of(), 0L, AgentKnowledge.maxPerKind(ctx.profile()));
        assertFalse(method.applicable(ctx), "an emptied sighting is not a way to obtain logs either");
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
    void itPicksTheNearerOfTwoEquallyFreshStores() {
        FakeContext ctx = new FakeContext();
        Pos near = new Pos(3, 64, 0);
        Pos far = new Pos(-20, 64, 0);
        seeStoreAt(ctx, near, 0L);
        seeStoreAt(ctx, far, 0L);
        ctx.percepts.time = 0L;

        TakeItems take = assertInstanceOf(TakeItems.class,
                new TakeFromStore(LOGS, 4).decompose(ctx).get(1));
        assertEquals(near, take.at(), "the nearer store wins when both beliefs are equally fresh");
    }

    @Test
    void itPicksTheFresherOfTwoEquidistantStores() {
        FakeContext ctx = new FakeContext();
        Pos stale = new Pos(6, 64, 0);
        Pos fresh = new Pos(-6, 64, 0);
        seeStoreAt(ctx, stale, 0L);
        seeStoreAt(ctx, fresh, 1000L);
        ctx.percepts.time = 1000L;

        TakeItems take = assertInstanceOf(TakeItems.class,
                new TakeFromStore(LOGS, 4).decompose(ctx).get(1));
        assertEquals(fresh, take.at(), "the fresher belief wins when both stores are equidistant");
    }

    @Test
    void itWalksToTheStoreAndTakesFromIt() {
        Pos at = new Pos(6, 64, 0);
        FakeContext ctx = ctxKnowing(at, 0L, 0L);
        List<Task> plan = new TakeFromStore(LOGS, 4).decompose(ctx);
        assertEquals(2, plan.size());

        GoTo go = assertInstanceOf(GoTo.class, plan.get(0));
        assertTrue(Math.abs(go.x() - at.x()) <= 1 && Math.abs(go.z() - at.z()) <= 1,
                "the walk targets a cell BESIDE the store, not the store block itself");

        TakeItems take = assertInstanceOf(TakeItems.class, plan.get(1));
        assertEquals(at, take.at());
        assertEquals(LOGS, take.spec());
        assertEquals(4, take.count());
    }
}
