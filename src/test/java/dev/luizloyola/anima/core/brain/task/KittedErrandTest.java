package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The kit wrap the arbiter puts around every granted item: needs first (hard), tried wants next
 * (soft), the errand last — and no wrapper at all when there is no kit, so every pre-kit item
 * runs exactly as it always did.
 */
class KittedErrandTest {

    private static final ItemSpec PICKS =
            ItemSpec.register(new ItemSpec("kitted-test-picks", id -> id.endsWith("_pickaxe")));
    private static final ItemSpec TORCHES =
            ItemSpec.register(new ItemSpec("kitted-test-torches", id -> id.equals("minecraft:torch")));

    private final FakeContext ctx = new FakeContext();

    /** A stand-in errand whose root is recognizable. */
    private record Item(Kit kit, Task root) implements WorkItem {
        @Override
        public double priority() {
            return 0.5;
        }

        @Override
        public String describe() {
            return "stand-in";
        }
    }

    private static final class Work implements PrimitiveTask {
        @Override
        public TaskStatus tick(BrainContext c) {
            return TaskStatus.SUCCESS;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "the errand";
        }
    }

    @Test
    void aKitlessItemComesBackUnwrapped() {
        Work root = new Work();
        assertSame(root, KittedErrand.around(new Item(Kit.NONE, root)),
                "no kit, no wrapper — pre-kit behaviour byte for byte");
    }

    @Test
    void needsComeFirstThenTriedWantsThenTheWork() {
        Work root = new Work();
        Task wrapped = KittedErrand.around(new Item(
                Kit.of(ItemCall.want(TORCHES, 8), ItemCall.need(PICKS, 1)), root));
        KittedErrand errand = assertInstanceOf(KittedErrand.class, wrapped);
        List<Task> plan = errand.methods().get(0).decompose(ctx);

        assertEquals(3, plan.size());
        ObtainItem pick = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertSame(PICKS, pick.spec(), "the NEED is a hard subgoal, ahead of everything");
        assertEquals(1, pick.count());
        Try torches = assertInstanceOf(Try.class, plan.get(1));
        assertSame(TORCHES, ((ObtainItem) torches.attempt()).spec(),
                "the WANT is wrapped — its failure must never fail the errand");
        assertSame(root, plan.get(2), "the errand itself runs last, kit in pack");
    }

    @Test
    void theWholeWrapRunsThroughTheRealExecutor() {
        // A want nobody can satisfy (no drops, no producer, no recipe) shrugs; the work runs.
        TaskExecutor executor = new TaskExecutor();
        Task wrapped = KittedErrand.around(new Item(Kit.of(ItemCall.want(TORCHES, 8)), new Work()));
        executor.run(wrapped, ctx);
        for (int i = 0; i < 50 && executor.isBusy(); i++) {
            executor.tick(ctx);
        }
        assertEquals(java.util.Optional.of(TaskStatus.SUCCESS), executor.lastStatus(),
                "an unobtainable want cost a shrug, not the errand");
    }
}
