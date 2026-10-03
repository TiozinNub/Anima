package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.WorkToleranceCurve;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a board is told when an errand is priced out of an item for good. */
class OutOfReachTest {

    private static final ItemSpec STONE = ItemSpec.register(
            new ItemSpec("out-of-reach-test-stone", id -> id.startsWith("test:stone")));

    private final FakeContext ctx = new FakeContext();

    /** A way that is always there and always 1000 blocks off. */
    private static Method farAway() {
        return new Method() {
            @Override
            public boolean applicable(BrainContext c) {
                return true;
            }

            @Override
            public double estimateCost(BrainContext c) {
                return 1000;
            }

            @Override
            public List<Task> decompose(BrainContext c) {
                return List.of();
            }

            @Override
            public String describe() {
                return "walk to the stone far off";
            }
        };
    }

    @BeforeEach
    void producers() {
        Producers.register(STONE, id -> true, wanted -> farAway());
    }

    @AfterEach
    void tearDown() {
        Producers.reset();
    }

    // ── the executor ─────────────────────────────────────────────────────────────────────────

    private static ItemCall call(ObtainItem obtain) {
        return ItemCall.need(obtain.spec(), obtain.count());
    }

    private TaskExecutor ran(Task root) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(root, ctx);
        for (int i = 0; i < 20 && executor.isBusy(); i++) {
            executor.tick(ctx);
        }
        return executor;
    }

    @Test
    void aPricedOutObtainNamesWhatItCouldNotAfford() {
        ctx.costTolerance = 150;
        TaskExecutor executor = ran(new ObtainItem(STONE, 8));

        assertEquals(Optional.of(ItemCall.need(STONE, 8)), executor.unaffordable().map(OutOfReachTest::call));

        TaskExecutor restored = new TaskExecutor();
        restored.restore(executor.snapshot());
        assertEquals(Optional.of(ItemCall.need(STONE, 8)), restored.unaffordable().map(OutOfReachTest::call),
                "a restart between the failure and its report keeps the item");
    }

    @Test
    void theObtainKeepsWhatItWasFor() {
        ctx.costTolerance = 150;
        TaskExecutor executor = ran(new ObtainItem(STONE, 3, Set.of("minecraft:stone_axe")));

        assertEquals(Optional.of(Set.of("minecraft:stone_axe")), executor.unaffordable().map(ObtainItem::pursued),
                "a recipe asked for it, which is what lets anybody seek it in an age that makes nothing of stone");
    }

    @Test
    void aPricedOutObtainThatWasShruggedOffNamesNothing() {
        ctx.costTolerance = 150;
        Task step = new CompoundTask() {
            @Override
            public List<Method> methods() {
                return List.of(new Method() {
                    @Override
                    public boolean applicable(BrainContext c) {
                        return true;
                    }

                    @Override
                    public double estimateCost(BrainContext c) {
                        return 0;
                    }

                    @Override
                    public List<Task> decompose(BrainContext c) {
                        return List.of(new Try(new ObtainItem(STONE, 8)), new ObtainItem(
                                ItemSpec.anyOf(Set.of("test:nowhere")), 1));
                    }

                    @Override
                    public String describe() {
                        return "try for stone, then something nobody makes";
                    }
                });
            }

            @Override
            public String describe() {
                return "errand";
            }
        };

        assertEquals(Optional.empty(), ran(step).unaffordable());
    }

    // ── one source for many asks ─────────────────────────────────────────────────────────────

    @Test
    void anAskByContentNamesTheSourceThatMakesIt() {
        assertEquals(Optional.of(STONE), Producers.sourceOf(STONE));
        assertEquals(Optional.of(STONE), Producers.sourceOf(ItemSpec.anyOf(Set.of("test:stone_a", "x:y"))),
                "a recipe's literal stone reaches the registered stone");
        assertEquals(Optional.empty(), Producers.sourceOf(ItemSpec.anyOf(Set.of("test:iron"))));
    }

    // ── the arbiter ──────────────────────────────────────────────────────────────────────────

    private static final class Board implements WorkSource {
        WorkItem offered;
        int steps;
        final List<ItemCall> pricedOutOf = new ArrayList<>();
        final List<Double> at = new ArrayList<>();

        @Override
        public Optional<WorkItem> bestAvailable(BrainContext c) {
            return Optional.ofNullable(offered);
        }

        @Override
        public void claimed(WorkItem item, BrainContext c) {
        }

        @Override
        public void completed(WorkItem item, BrainContext c) {
            offered = null;
        }

        @Override
        public void failed(WorkItem item, BrainContext c) {
            offered = null;
        }

        @Override
        public void pricedOutOf(WorkItem item, ObtainItem wanted, double tolerance, BrainContext c) {
            pricedOutOf.add(call(wanted));
            at.add(tolerance);
        }

        @Override
        public int budgetSteps(WorkItem item) {
            return steps;
        }
    }

    private record Item(double priority, OptionalDouble tolerance, Supplier<Task> plan) implements WorkItem {
        Item(double priority, OptionalDouble tolerance) {
            this(priority, tolerance, () -> new ObtainItem(STONE, 8));
        }

        @Override
        public Task root() {
            return plan.get();
        }

        @Override
        public String describe() {
            return "make a stone axe";
        }

        @Override
        public Deed doing() {
            return Deed.of(FakeDoings.DID_IT);
        }
    }

    private Board flown(Item item, int steps) {
        Board board = new Board();
        board.steps = steps;
        board.offered = item;
        Arbiter arbiter = new Arbiter(List.of(), board);
        ctx.costTolerance = 150; // the fake does not ask the arbiter
        for (int i = 0; i < 4; i++) {
            arbiter.tick(ctx);
        }
        return board;
    }

    @Test
    void anErrandPricedOutSaysOfWhatAndAtWhatBudget() {
        Board board = flown(new Item(0.46, OptionalDouble.empty()), WorkToleranceCurve.MAX_STEPS);
        assertEquals(List.of(ItemCall.need(STONE, 8)), board.pricedOutOf);
        assertEquals(List.of(WorkToleranceCurve.CAP), board.at, "at the cap: no wait will make it affordable");

        Board early = flown(new Item(0.46, OptionalDouble.empty()), 2);
        assertEquals(List.of(WorkToleranceCurve.tolerance(0.46, 2)), early.at,
                "before the steps are grown, so the board reads the budget it failed at");
    }

    @Test
    void anErrandWithItsOwnReachSaysNothingAndSpendsIt() {
        Item far = new Item(0.46, OptionalDouble.of(512));
        Board board = flown(far, WorkToleranceCurve.MAX_STEPS);
        assertTrue(board.pricedOutOf.isEmpty(), "it was already the far errand");

        Board held = new Board();
        held.offered = new Item(0.46, OptionalDouble.of(512), () -> new PrimitiveTask() {
            @Override
            public TaskStatus tick(BrainContext c) {
                return TaskStatus.RUNNING;
            }

            @Override
            public void cancel(BrainContext c) {
            }

            @Override
            public String describe() {
                return "walking out";
            }
        });
        Arbiter arbiter = new Arbiter(List.of(), held);
        arbiter.tick(ctx);
        assertEquals(512, arbiter.costTolerance(ctx));
    }
}
