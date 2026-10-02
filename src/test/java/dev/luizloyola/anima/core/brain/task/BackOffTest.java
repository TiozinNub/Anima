package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * A drive priced out of every way backs off, doubling to a cap, and is free again the moment what
 * it could draw on changes or its budget grows. Forest, 2026-10-02: a hungry settler with no food
 * to hand preempted her work every 100 ticks to fail at once, 371 times.
 */
class BackOffTest {

    private final FakeContext ctx = new FakeContext();

    /** Eat with nothing affordable: its one way costs 100 and it will pay {@link #budget}. */
    private static final class Hungry implements Instinct {
        long stock;
        double budget = 15;
        int grants;
        boolean backsOff = true;
        boolean affordable;

        @Override
        public double pressure(BrainContext ctx) {
            return 0.3;
        }

        @Override
        public Task root(BrainContext ctx) {
            grants++;
            return new CompoundTask() {
                @Override
                public List<Method> methods() {
                    return List.of(new Method() {
                        @Override
                        public boolean applicable(BrainContext ctx) {
                            return true;
                        }

                        @Override
                        public double estimateCost(BrainContext ctx) {
                            return affordable ? 0 : 100;
                        }

                        @Override
                        public List<Task> decompose(BrainContext ctx) {
                            return List.of(new PrimitiveTask() {
                                @Override
                                public TaskStatus tick(BrainContext ctx) {
                                    return TaskStatus.SUCCESS;
                                }

                                @Override
                                public void cancel(BrainContext ctx) {
                                }

                                @Override
                                public String describe() {
                                    return "eat it";
                                }
                            });
                        }

                        @Override
                        public String describe() {
                            return "hunt";
                        }
                    });
                }

                @Override
                public String describe() {
                    return "satisfy hunger";
                }
            };
        }

        @Override
        public double costTolerance(BrainContext ctx) {
            return budget;
        }

        @Override
        public OptionalLong stock(BrainContext ctx) {
            return backsOff ? OptionalLong.of(stock) : OptionalLong.empty();
        }

        @Override
        public Deed doing(BrainContext ctx) {
            return Deed.of(Doings.EATING);
        }

        @Override
        public String describe() {
            return "eat";
        }
    }

    /** Ticks on, publishing the drive's budget as the driver's context does. */
    private void run(Arbiter arbiter, Hungry eat, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.percepts.time++;
            ctx.costTolerance = eat.budget;
            arbiter.tick(ctx);
        }
    }

    @Test
    void pricedOutItWaitsLongerEachTimeInARow() {
        Hungry eat = new Hungry();
        Arbiter arbiter = new Arbiter(List.of(eat));

        run(arbiter, eat, 1);
        assertEquals(1, eat.grants);
        assertEquals(Arbiter.BACK_OFF, arbiter.cooldowns().get("Hungry"), "not the 100 a plain failure waits");

        run(arbiter, eat, Arbiter.BACK_OFF + 1);
        assertEquals(2, eat.grants, "once the wait is over it tries again");
        assertEquals(2 * Arbiter.BACK_OFF, arbiter.cooldowns().get("Hungry"), "and fails into twice the wait");

        run(arbiter, eat, 30_000);
        assertTrue(arbiter.cooldowns().get("Hungry") <= 8 * Arbiter.BACK_OFF, "capped at 4,800");
    }

    @Test
    void aFailureNotOnPriceWaitsThePlainCooldown() {
        Hungry eat = new Hungry();
        eat.backsOff = false;
        Arbiter arbiter = new Arbiter(List.of(eat));

        run(arbiter, eat, 1);

        assertEquals(Instinct.DEFAULT_FAIL_COOLDOWN, arbiter.cooldowns().get("Hungry"));
        assertTrue(arbiter.backOffs().isEmpty());
    }

    @Test
    void foodTurningUpEndsTheWaitWithinASecond() {
        Hungry eat = new Hungry();
        Arbiter arbiter = new Arbiter(List.of(eat));
        run(arbiter, eat, 1);
        run(arbiter, eat, Arbiter.BACK_OFF + 1);
        assertEquals(2, eat.grants);

        eat.stock = 7; // a cook carried the mutton home
        eat.affordable = true;
        run(arbiter, eat, 20);

        assertTrue(eat.grants >= 3, "a meal is never kept waiting past the food arriving");
        assertTrue(arbiter.backOffs().isEmpty(), "and the count starts again");
    }

    @Test
    void aBiggerBudgetEndsTheWait() {
        Hungry eat = new Hungry();
        Arbiter arbiter = new Arbiter(List.of(eat));
        run(arbiter, eat, 1);

        eat.budget = 64; // hungry to starving
        run(arbiter, eat, 20);

        assertEquals(2, eat.grants, "what was priced out may be affordable now");
    }

    @Test
    void aRestartKeepsTheWaitAndTheCount() {
        Hungry eat = new Hungry();
        Arbiter arbiter = new Arbiter(List.of(eat));
        run(arbiter, eat, 1);
        run(arbiter, eat, Arbiter.BACK_OFF + 1);

        Hungry again = new Hungry();
        Arbiter restored = new Arbiter(List.of(again));
        restored.restoreCooldowns(arbiter.cooldowns());
        restored.restoreBackOffs(arbiter.backOffs());
        run(restored, again, 2 * Arbiter.BACK_OFF);
        assertEquals(0, again.grants, "the second wait outlived the restart");
        run(restored, again, 1);
        assertEquals(1, again.grants);
        assertEquals(4 * Arbiter.BACK_OFF, restored.cooldowns().get("Hungry"), "and the third doubled it");
    }

    @Test
    void theEatReadingMovesWithThePackTheStoresAndTheDepot() {
        ctx.percepts.food("minecraft:beef", new dev.luizloyola.anima.core.agent.FoodValue(3, 1.8F, false));
        ctx.percepts.cooked("minecraft:beef", new dev.luizloyola.anima.core.agent.FoodValue(8, 12.8F, false));
        ctx.percepts.food("minecraft:cooked_beef", new dev.luizloyola.anima.core.agent.FoodValue(8, 12.8F, false));
        ReadyFood.install(ctx.percepts.foods());
        try {
            long empty = SatisfyHunger.stock(ctx);

            ctx.held.put(Food.SPEC.name(), 4L);
            long delivered = SatisfyHunger.stock(ctx);
            assertNotEquals(empty, delivered, "another member's delivery home");

            Pos chest = new Pos(4, 64, 0);
            ctx.claim(Store.POI, chest);
            ctx.knowledge.sawInside(chest, List.of(ItemStack.of("minecraft:beef", 2, 64)), 0L, 64);
            long seen = SatisfyHunger.stock(ctx);
            assertNotEquals(delivered, seen, "food seen in a store");

            ctx.knowledge.sawInside(chest, List.of(ItemStack.of("minecraft:beef", 1, 64)), 1L, 64);
            ctx.percepts.inventory.set(0, ItemStack.of("minecraft:beef", 1, 64));
            long taken = SatisfyHunger.stock(ctx);
            assertNotEquals(seen, taken, "one taken out to be cooked is still a change");

            ctx.percepts.inventory.set(0, ItemStack.of("minecraft:cooked_beef", 1, 64));
            assertNotEquals(taken, SatisfyHunger.stock(ctx), "and so is cooking it: a meal is ready now");
        } finally {
            ReadyFood.install(null);
        }
    }
}
