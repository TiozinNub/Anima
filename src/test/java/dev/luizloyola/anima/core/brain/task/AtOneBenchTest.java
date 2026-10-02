package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.Recipes;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Crafts run at one bench put the table down once and pick it up once, after the last. */
class AtOneBenchTest {

    private static final int Y = FakeProbe.GROUND_Y + 1;

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private static CraftRecipe atTable(String output) {
        return new CraftRecipe(output, ItemStack.of(output, 1, 1),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_planks"), 3),
                        new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 2)), true);
    }

    private static ObtainItem one(String id) {
        return new ObtainItem(ItemSpec.anyOf(Set.of(id)), 1);
    }

    @BeforeEach
    void stock() {
        List<CraftRecipe> book = List.of(atTable("minecraft:wooden_axe"),
                atTable("minecraft:wooden_pickaxe"));
        Recipes.provide(spec -> book.stream().filter(r -> spec.matches(r.outputId())).toList());
        ctx.tablesPackedUpAbove = executor::tablesPackedUpAbove;
        ctx.percepts.position = new Pos(10, Y, 10);
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_planks", 6, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:stick", 4, 64));
        ctx.percepts.inventory.add(ItemStack.of(Workbench.ITEM_ID, 1, 64));
    }

    @AfterEach
    void tearDown() {
        Recipes.reset();
        Producers.reset();
    }

    /** Runs the plan, putting each placed table into the world and taking each broken one out. */
    private Optional<TaskStatus> run(Task plan) {
        executor.run(plan, ctx);
        int placed = 0;
        for (int tick = 0; tick < 400 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            for (; placed < ctx.placer.placed.size(); placed++) {
                Pos at = ctx.placer.placed.get(placed).cell();
                ctx.percepts.blocks.set(at.x(), at.y(), at.z(), Workbench.BLOCK);
                ctx.percepts.inventory.remove(Workbench.ITEM_ID, 1);
            }
            if (ctx.breaker.state == BreakState.BREAKING) {
                Pos at = ctx.breaker.target;
                ctx.percepts.blocks.set(at.x(), at.y(), at.z(), BlockKind.AIR);
                ctx.breaker.state = BreakState.FINISHED;
            }
            executor.tick(ctx);
        }
        return executor.lastStatus();
    }

    @Test
    void twoTableCraftsPutTheTableDownOnceAndPickItUpOnce() {
        Optional<TaskStatus> outcome = run(new AtOneBench(List.of(
                one("minecraft:wooden_axe"), one("minecraft:wooden_pickaxe"))));

        assertEquals(Optional.of(TaskStatus.SUCCESS), outcome);
        assertEquals(1, ctx.percepts.inventory.count("minecraft:wooden_axe"));
        assertEquals(1, ctx.percepts.inventory.count("minecraft:wooden_pickaxe"));
        assertEquals(1, ctx.placer.placed.size(), "the second craft used the table the first put down");
        assertEquals(1, ctx.breaker.targets.size(), "picked up once, after the last craft");
        assertTrue(ctx.fieldTables.snapshot().isEmpty(), "nothing left standing");
    }

    @Test
    void anErrandThatFailsDoesNotStopTheNext() {
        Optional<TaskStatus> outcome = run(new AtOneBench(List.of(
                one("minecraft:diamond_sword"), one("minecraft:wooden_axe"))));

        assertEquals(Optional.of(TaskStatus.SUCCESS), outcome, "the run judges nothing itself");
        assertEquals(1, ctx.percepts.inventory.count("minecraft:wooden_axe"));
        assertEquals(1, ctx.breaker.targets.size());
        assertTrue(ctx.fieldTables.snapshot().isEmpty());
    }
}
