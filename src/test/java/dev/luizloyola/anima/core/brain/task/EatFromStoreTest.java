package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.brain.act.ConsumeState;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.core.social.Places;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A hungry body with nothing to eat in hand goes to one of the party's stores for ready food and
 * eats it there. Which store is {@link TakeFromStoreTest}'s subject; here it is the meal.
 */
class EatFromStoreTest {

    private static final ItemStack BREAD = ItemStack.of("minecraft:bread", 3, 64);

    private final FakeContext ctx = new FakeContext();
    private final Places places = new Places();
    private final TaskExecutor executor = new TaskExecutor();

    @BeforeEach
    void setUp() {
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
        ctx.percepts.food("minecraft:bread", new FoodValue(5, 6.0F, false));
        ctx.percepts.food("minecraft:beef", new FoodValue(3, 1.8F, false));
        ctx.percepts.cooked("minecraft:beef", new FoodValue(8, 12.8F, false));
        ReadyFood.install(ctx.percepts.foods());
        ctx.percepts.metabolism.setFoodLevel(8);
        ctx.mover.setState(MoveState.ARRIVED);
    }

    @AfterEach
    void uninstall() {
        ReadyFood.install(null);
    }

    /** One of the party's stores, with {@code inside} in the world and, if seen, in memory too. */
    private void store(Pos at, boolean seen, ItemStack... inside) {
        places.viewFor(ctx.self).foundCommunal(Store.POI, at, 0L);
        ctx.containers.boxes.put(at, new ArrayList<>(List.of(inside)));
        if (seen) {
            ctx.knowledge.sawInside(at, List.of(inside), 0L, AgentKnowledge.maxPerKind(ctx.profile()));
        }
    }

    /** Ticks until the bite starts, finishes it, and ticks until the meal is over. */
    private void runTheMeal() {
        executor.run(new SatisfyHunger(), ctx);
        for (int tick = 0; tick < 400 && ctx.consumer.beginCalls == 0 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            executor.tick(ctx);
        }
        ctx.consumer.setState(ConsumeState.FINISHED);
        for (int tick = 0; tick < 10 && executor.isBusy(); tick++) {
            executor.tick(ctx);
        }
    }

    @Test
    void itIsAppendedAfterTheWaysAlreadySaved() {
        List<Method> methods = new SatisfyHunger().methods();
        assertInstanceOf(EatReadyFood.class, methods.get(0));
        assertInstanceOf(EatLastResort.class, methods.get(1));
        assertInstanceOf(EatFromStore.class, methods.get(2));
    }

    @Test
    void aFullBarGoesToNoStore() {
        store(new Pos(6, 64, 0), true, BREAD);
        ctx.percepts.metabolism.setFoodLevel(20);
        assertFalse(new EatFromStore().applicable(ctx));
    }

    @Test
    void itCostsTheWalkToTheStore() {
        store(new Pos(6, 64, 0), true, BREAD);
        assertTrue(new EatFromStore().applicable(ctx));
        assertEquals(6.0, new EatFromStore().estimateCost(ctx), 1e-9);
    }

    @Test
    void itFetchesBreadAndEatsIt() {
        Pos at = new Pos(6, 64, 0);
        store(at, true, BREAD);

        runTheMeal();

        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
        assertEquals(1, ctx.consumer.beginCalls);
        assertEquals("minecraft:bread", ctx.percepts.inventory.get(ctx.consumer.lastSlot).id(),
                "the bite is the bread just fetched");
        assertEquals(2, ctx.containers.boxes.get(at).get(0).count(), "one loaf taken, two left");
    }

    @Test
    void rawMeatInHandWaitsWhileAStoreHasBread() {
        ctx.costTolerance = 60.0; // hungry, not starving: raw meat is priced at 100 and waits
        ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 2, 64));
        store(new Pos(6, 64, 0), true, BREAD);

        runTheMeal();

        assertEquals("minecraft:bread", ctx.percepts.inventory.get(ctx.consumer.lastSlot).id());
    }

    @Test
    void aNearChestWithNoFoodSendsItOnToTheOneWithBread() {
        Pos near = new Pos(-3, 64, 0);
        Pos known = new Pos(10, 64, 0);
        store(near, false, ItemStack.of("minecraft:cobblestone", 9, 64));
        store(known, true, BREAD);

        runTheMeal();

        assertEquals(List.of(near, known), ctx.containers.opened,
                "4.5 against 10, so the unopened chest first — and on to the bread in one meal");
        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
    }

    @Test
    void readyFoodInHandIsEatenWithoutAWalk() {
        ctx.percepts.inventory.set(4, ItemStack.of("minecraft:bread", 1, 64));
        store(new Pos(6, 64, 0), true, BREAD);

        runTheMeal();

        assertEquals(4, ctx.consumer.lastSlot);
        assertTrue(ctx.containers.opened.isEmpty(), "no store is opened for food already carried");
    }
}
