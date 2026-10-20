package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.Recipes;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.inv.Kit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The kit wrap the arbiter puts around every granted item: needs first (hard), tried wants next
 * (soft), the needs again and checked, the errand last — and no wrapper at all when there is no
 * kit, so every pre-kit item runs exactly as it always did.
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

        @Override
        public Deed doing() {
            return Deed.of(FakeDoings.IDLED);
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

    /** An errand the consumer lets build: its walks may lay blocks. */
    private record BuildingItem(Task root) implements WorkItem {
        @Override
        public double priority() {
            return 0.5;
        }

        @Override
        public String describe() {
            return "stand-in that builds";
        }

        @Override
        public Deed doing() {
            return Deed.of(FakeDoings.IDLED);
        }

        @Override
        public boolean buildsOnTheWay() {
            return true;
        }
    }

    @Test
    void anErrandThatBuildsIsWrappedWithoutAKitAndTheChainSaysSo() {
        Work root = new Work();
        KittedErrand errand = assertInstanceOf(KittedErrand.class,
                KittedErrand.around(new BuildingItem(root)));
        assertEquals(List.of(root), errand.methods().get(0).decompose(ctx), "nothing to kit up");
        assertEquals("the errand", errand.describe());
        TaskExecutor executor = new TaskExecutor();
        executor.run(new KittedErrand(List.of(), new GoTo(1, 2, 3), true), ctx);
        executor.tick(ctx);
        org.junit.jupiter.api.Assertions.assertTrue(executor.building(),
                "a walk deep in the errand is the errand's");
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

        assertEquals(5, plan.size());
        ObtainItem pick = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertSame(PICKS, pick.spec(), "the NEED is a hard subgoal, ahead of everything");
        assertEquals(1, pick.count());
        Try torches = assertInstanceOf(Try.class, plan.get(1));
        assertSame(TORCHES, ((ObtainItem) torches.attempt()).spec(),
                "the WANT is wrapped — its failure must never fail the errand");
        assertSame(PICKS, assertInstanceOf(ObtainItem.class, plan.get(2)).spec(), "the need, again");
        assertInstanceOf(KittedErrand.KitHeld.class, plan.get(3));
        assertSame(root, plan.get(4), "the errand itself runs last, kit in pack");
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

    @AfterEach
    void tearDown() {
        Recipes.reset();
        Producers.reset();
    }

    private static CraftRecipe inHand(String output, int count, String from, int needed) {
        return new CraftRecipe(output, ItemStack.of(output, count, 64),
                List.of(new CraftRecipe.Ingredient(Set.of(from), needed)), false);
    }

    private static ItemSpec only(String id) {
        return ItemSpec.anyOf(Set.of(id));
    }

    /** The errand: what the pack held when it began. */
    private final class Counted implements PrimitiveTask {
        final List<Integer> counts = new ArrayList<>();
        private final List<String> ids;

        Counted(String... ids) {
            this.ids = List.of(ids);
        }

        @Override
        public TaskStatus tick(BrainContext c) {
            ids.forEach(id -> counts.add(c.percepts().inventory().count(id)));
            return TaskStatus.SUCCESS;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "count the kit";
        }
    }

    private Optional<TaskStatus> fly(Task plan) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(plan, ctx);
        for (int i = 0; i < 2000 && executor.isBusy(); i++) {
            ctx.percepts.time++;
            executor.tick(ctx);
        }
        return executor.lastStatus();
    }

    @Test
    void aNeedALaterNeedsCraftSpentIsGotAgainBeforeTheWork() {
        // The house piece's kit: 9 planks, then fences crafted from planks.
        List<CraftRecipe> book = List.of(inHand("test:planks", 4, "test:log", 1),
                inHand("test:fence", 3, "test:planks", 4));
        Recipes.provide(spec -> book.stream().filter(r -> spec.matches(r.outputId())).toList());
        ctx.percepts.inventory.add(ItemStack.of("test:log", 3, 64));
        Counted work = new Counted("test:planks", "test:fence");

        assertEquals(Optional.of(TaskStatus.SUCCESS), fly(new KittedErrand(List.of(
                ItemCall.need(only("test:planks"), 4), ItemCall.need(only("test:fence"), 3)), work)));
        assertEquals(List.of(4, 3), work.counts, "the planks the fences took were made again");
    }

    @Test
    void aKitThatCannotHoldFailsLoudlyBeforeTheWork() {
        // Each is made only from the other: getting one always spends the one got before it.
        List<CraftRecipe> book = List.of(inHand("test:a", 1, "test:b", 1), inHand("test:b", 1, "test:a", 1));
        Recipes.provide(spec -> book.stream().filter(r -> spec.matches(r.outputId())).toList());
        ctx.percepts.inventory.add(ItemStack.of("test:b", 1, 64));
        Counted work = new Counted("test:a");

        assertEquals(Optional.of(TaskStatus.FAILED), fly(new KittedErrand(List.of(
                ItemCall.need(only("test:a"), 1), ItemCall.need(only("test:b"), 1)), work)));
        assertTrue(work.counts.isEmpty(), "the work never began");
        assertTrue(ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .anyMatch(e -> e.detail().equals("the kit fell short of 1 test:a")));
    }
}
