package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Taking a thing out of one of the party's stores: only the party's, priced at distance plus the
 * look's age when it was seen there, at 1.5× distance when nobody has looked, never above that for
 * age, and ruled out for {@code stores.recheck_ticks} after a look that found none. Appended after
 * {@link CraftFor} in {@link ObtainItem}'s method list — new ways are always appended, never
 * inserted, so a saved plan's earlier indices hold.
 */
class TakeFromStoreTest {

    private static final ItemSpec LOGS = ItemSpec.register(
            new ItemSpec("take-from-store-test-logs", id -> id.endsWith("_log")));

    private final FakeContext ctx = new FakeContext();
    private final Places places = new Places();

    TakeFromStoreTest() {
        PartyId party = PartyId.random();
        places.asks(new Places.Parties() {
            @Override
            public Optional<PartyId> current(AgentId who) {
                return Optional.of(party);
            }

            @Override
            public PartyId of(AgentId who) {
                return party;
            }
        });
        ctx.knowledge.sees(places.viewFor(ctx.self), () -> ctx.percepts.time);
    }

    private void claim(Pos at) {
        places.viewFor(ctx.self).foundCommunal(Store.POI, at, 0L);
    }

    private void look(Pos at, long seenTick, ItemStack... inside) {
        ctx.knowledge.sawInside(at, List.of(inside), seenTick,
                AgentKnowledge.maxPerKind(ctx.profile()));
    }

    /** One of the party's stores, last opened at {@code seenTick}, holding nine logs. */
    private void storeWithLogs(Pos at, long seenTick) {
        claim(at);
        look(at, seenTick, ItemStack.of("minecraft:oak_log", 9, 64));
    }

    private TakeItems chosen() {
        return assertInstanceOf(TakeItems.class, new TakeFromStore(LOGS, 4).decompose(ctx).get(1));
    }

    private double cost() {
        return new TakeFromStore(LOGS, 4).estimateCost(ctx);
    }

    @Test
    void takeFromStoreIsAppendedLast() {
        // Only pins TakeFromStore's OWN relative position — last among ObtainItem's ways. The
        // absolute-index guarantee a saved plan depends on is pinned in ObtainItemTest's
        // aLiteralIngredientReachesTheConsumersProducerByContent, not here.
        List<Method> methods = new ObtainItem(LOGS, 4, java.util.Set.of()).methods();
        assertTrue(methods.get(methods.size() - 1) instanceof TakeFromStore);
    }

    @Test
    void aStoresOnlyObtainHasThisOneWay() {
        List<Method> methods = new ObtainItem(LOGS, 4, java.util.Set.of(),
                ObtainItem.Sources.STORES).methods();
        assertEquals(1, methods.size(), "a saved plan resumes by index, so index 0 must be it");
        assertInstanceOf(TakeFromStore.class, methods.get(0));
    }

    @Test
    void aStoreSomebodyElsePlacedIsNeverAWay() {
        Pos theirs = new Pos(6, 64, 0);
        ctx.knowledge.note(new PoiMemory(Store.POI, theirs, Region.of(theirs), 1, false, 0L),
                AgentKnowledge.maxPerKind(ctx.profile()));
        look(theirs, 0L, ItemStack.of("minecraft:oak_log", 9, 64));

        assertFalse(new TakeFromStore(LOGS, 4).applicable(ctx),
                "seen full of logs, but not the party's: taking them would be stealing");
        assertFalse(TakeFromStore.seenHolding(ctx, LOGS));
    }

    @Test
    void anUnopenedStoreOfThePartysIsWorthALook() {
        Pos at = new Pos(6, 64, 0);
        assertFalse(new TakeFromStore(LOGS, 4).applicable(ctx), "no store at all");

        claim(at);
        assertTrue(new TakeFromStore(LOGS, 4).applicable(ctx));
        assertEquals(1.5 * 6, cost(), 1e-9);
        assertTrue(chosen().looking(), "finding none in it is an answer, not a failure");
    }

    @Test
    void anUnopenedStoreIsNotEvidenceTheThingExists() {
        claim(new Pos(6, 64, 0));
        assertFalse(TakeFromStore.seenHolding(ctx, LOGS),
                "a recipe must not count on logs nobody has seen");

        storeWithLogs(new Pos(-6, 64, 0), 0L);
        assertTrue(TakeFromStore.seenHolding(ctx, LOGS));
    }

    @Test
    void anUnopenedStoreFiveAwayBeatsAKnownOneTenAway() {
        Pos known = new Pos(10, 64, 0);
        Pos unopened = new Pos(-5, 64, 0);
        storeWithLogs(known, 0L);
        claim(unopened);

        assertEquals(unopened, chosen().at(), "7.5 against 10: opening it may end the trip sooner");
    }

    @Test
    void atTheSameDistanceTheKnownStoreWins() {
        Pos known = new Pos(10, 64, 0);
        Pos unopened = new Pos(-10, 64, 0);
        storeWithLogs(known, 0L);
        claim(unopened);

        TakeItems take = chosen();
        assertEquals(known, take.at());
        assertFalse(take.looking(), "a store seen holding logs and found without is a wrong belief");
    }

    @Test
    void ageNeverPricesALookAboveNotHavingLooked() {
        Pos known = new Pos(10, 64, 0);
        Pos unopened = new Pos(-10, 64, 0);
        storeWithLogs(known, 0L);
        claim(unopened);
        ctx.percepts.time = 1_000_000L;

        assertEquals(15.0, cost(), 1e-9, "capped at the unopened price, not 10 + 5000");
        assertEquals(known, chosen().at(), "and at that tie the look still wins");
    }

    @Test
    void aStaleBeliefCostsMoreThanAFreshOneAtTheSameDistance() {
        storeWithLogs(new Pos(6, 64, 0), 0L);
        ctx.percepts.time = 0L;
        double fresh = cost();
        ctx.percepts.time = 1000L;
        assertTrue(cost() > fresh,
                "distance alone would send a settler to a chest emptied an hour ago");
    }

    @Test
    void aRecentLookThatFoundNoneRulesTheStoreOut() {
        Pos at = new Pos(6, 64, 0);
        claim(at);
        look(at, 0L, ItemStack.of("minecraft:cobblestone", 9, 64));
        int recheck = ctx.profile().i(ProfileAspect.STORES_RECHECK_TICKS);

        ctx.percepts.time = recheck - 1;
        assertFalse(new TakeFromStore(LOGS, 4).applicable(ctx), "a chest of cobble, looked at lately");

        ctx.percepts.time = recheck;
        assertTrue(new TakeFromStore(LOGS, 4).applicable(ctx),
                "somebody may have filled it since: as good as unopened");
        assertEquals(1.5 * 6, cost(), 1e-9);
        assertTrue(chosen().looking());
    }

    @Test
    void aStoreShutToUsIsNotTriedAgainUntilItsTimerRunsOut() {
        Pos at = new Pos(6, 64, 0);
        claim(at);
        ctx.knowledge.avoid(Store.POI, at, 100L);

        assertFalse(new TakeFromStore(LOGS, 4).applicable(ctx),
                "unopened forever, so it would be the cheapest way every round until the cap");
        ctx.percepts.time = 100L;
        assertTrue(new TakeFromStore(LOGS, 4).applicable(ctx));
    }

    @Test
    void itPicksTheNearerOfTwoEquallyFreshStores() {
        Pos near = new Pos(3, 64, 0);
        storeWithLogs(near, 0L);
        storeWithLogs(new Pos(-20, 64, 0), 0L);

        assertEquals(near, chosen().at());
    }

    @Test
    void itPicksTheFresherOfTwoEquidistantStores() {
        Pos fresh = new Pos(-6, 64, 0);
        storeWithLogs(new Pos(6, 64, 0), 0L);
        storeWithLogs(fresh, 1000L);
        ctx.percepts.time = 1000L;

        assertEquals(fresh, chosen().at());
    }

    @Test
    void itWalksToTheStoreAndTakesFromIt() {
        Pos at = new Pos(6, 64, 0);
        storeWithLogs(at, 0L);
        List<Task> plan = new TakeFromStore(LOGS, 4).decompose(ctx);
        assertEquals(2, plan.size());

        GoTo go = assertInstanceOf(GoTo.class, plan.get(0));
        assertTrue(Math.abs(go.x() - at.x()) <= 1 && Math.abs(go.z() - at.z()) <= 1
                        && !(go.x() == at.x() && go.z() == at.z()),
                "the walk targets a cell BESIDE the store, not the store block itself");

        TakeItems take = assertInstanceOf(TakeItems.class, plan.get(1));
        assertEquals(at, take.at());
        assertEquals(LOGS, take.spec());
        assertEquals(4, take.count());
    }

    @Test
    void anObtainThatMayNotEmptyStoresKeepsTheMethodButNeverPicksIt() {
        storeWithLogs(new Pos(6, 64, 0), 0L);

        List<Method> roster = new ObtainItem(LOGS, 4, java.util.Set.of(),
                ObtainItem.Sources.NOT_STORES).methods();
        Method last = roster.get(roster.size() - 1);
        assertInstanceOf(TakeFromStore.class, last,
                "the method stays at its index — a saved plan resumes by index, so nothing is removed");
        assertFalse(last.applicable(ctx),
                "a store full of logs is not a source for an errand whose job is to fill one");
        assertEquals(Double.MAX_VALUE, last.estimateCost(ctx),
                "and it prices itself out, so cost-based selection never reaches it");
    }

    @Test
    void theSameStoreIsStillFairGameForAnOrdinaryObtain() {
        storeWithLogs(new Pos(6, 64, 0), 0L);

        List<Method> roster = new ObtainItem(LOGS, 4).methods();
        Method last = roster.get(roster.size() - 1);
        assertTrue(last.applicable(ctx), "the default is unchanged: any obtain may raid a store");
    }

    @Test
    void aLookThatFindsNoneSendsTheBodyOnToTheKnownStore() {
        Pos known = new Pos(10, 64, 0);
        Pos unopened = new Pos(-5, 64, 0);
        storeWithLogs(known, 0L);
        claim(unopened);
        ctx.containers.boxes.put(unopened,
                new ArrayList<>(List.of(ItemStack.of("minecraft:cobblestone", 9, 64))));
        ctx.containers.boxes.put(known,
                new ArrayList<>(List.of(ItemStack.of("minecraft:oak_log", 9, 64))));
        ctx.mover.setState(MoveState.ARRIVED);

        TaskExecutor executor = new TaskExecutor();
        executor.run(new ObtainItem(LOGS, 4, java.util.Set.of(), ObtainItem.Sources.STORES), ctx);
        for (int tick = 0; tick < 400 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            executor.tick(ctx);
        }

        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
        assertEquals(List.of(unopened, known), ctx.containers.opened,
                "the near chest first, and on to the known one in the same errand");
        assertEquals(4, ctx.percepts.inventory.count(LOGS.matcher()));
    }
}
