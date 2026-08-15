package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.Recipes;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The making method, headless: applicability, the 2×2 filter, covered-beats-missing pricing,
 * and the ANCESTOR-based occurs-check — the gold-ingot ⇄ gold-nugget cycle refuses, the
 * chop-one-log-for-the-axe chain does not (a sibling obtain is not an ancestor).
 */
class CraftForTest {

    private static final ItemSpec PLANKS =
            ItemSpec.register(new ItemSpec("craft-test-planks", id -> id.endsWith("_planks")));
    private static final ItemSpec STICKS =
            ItemSpec.register(new ItemSpec("craft-test-sticks", id -> id.equals("minecraft:stick")));
    private static final ItemSpec INGOTS =
            ItemSpec.register(new ItemSpec("craft-test-ingots",
                    id -> id.equals("minecraft:gold_ingot")));

    private static CraftRecipe planksFromLog() {
        return new CraftRecipe("minecraft:oak_planks",
                ItemStack.of("minecraft:oak_planks", 4, 64),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_log"), 1)), false);
    }

    private static CraftRecipe sticksFromPlanks() {
        return new CraftRecipe("minecraft:stick", ItemStack.of("minecraft:stick", 4, 64),
                List.of(new CraftRecipe.Ingredient(
                        Set.of("minecraft:oak_planks", "minecraft:birch_planks"), 2)), false);
    }

    private static CraftRecipe axeNeedingTable() {
        return new CraftRecipe("minecraft:wooden_axe", ItemStack.of("minecraft:wooden_axe", 1, 1),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:oak_planks"), 3),
                        new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 2)), true);
    }

    private static CraftRecipe ingotFromNuggets() {
        return new CraftRecipe("minecraft:gold_ingot", ItemStack.of("minecraft:gold_ingot", 1, 64),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:gold_nugget"), 9)), false);
    }

    private static CraftRecipe nuggetsFromIngot() {
        return new CraftRecipe("minecraft:gold_nugget", ItemStack.of("minecraft:gold_nugget", 9, 64),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:gold_ingot"), 1)), false);
    }

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        Recipes.reset();
    }

    private static void book(CraftRecipe... recipes) {
        List<CraftRecipe> all = List.of(recipes);
        Recipes.provide(spec -> all.stream().filter(r -> spec.matches(r.outputId())).toList());
    }

    @Test
    void notApplicableWithoutAnyRecipe() {
        assertFalse(new CraftFor(PLANKS, 4, Set.of()).applicable(ctx), "empty book");
    }

    @Test
    void aTableRecipePlansItsBenchBetweenTheBillAndTheExchange() {
        book(axeNeedingTable());
        CraftFor axe = new CraftFor(ItemSpec.anyOf(Set.of("minecraft:wooden_axe")), 1, Set.of());
        assertTrue(axe.applicable(ctx), "the table era: the whole book is reachable");
        List<Task> plan = axe.decompose(ctx);
        assertEquals(6, plan.size());
        assertInstanceOf(ObtainItem.class, plan.get(0), "planks");
        assertInstanceOf(ObtainItem.class, plan.get(1), "sticks");
        EnsureTable bench = assertInstanceOf(EnsureTable.class, plan.get(2));
        assertTrue(bench.pursued().contains("minecraft:wooden_axe"),
                "the occurs-check threads through the bench — a table recipe for the table halts");
        assertInstanceOf(ObtainItem.class, plan.get(3),
                "the bill again: making the bench may have EATEN it (the table is planks)");
        assertInstanceOf(ObtainItem.class, plan.get(4));
        assertInstanceOf(CraftStep.class, plan.get(5));
    }

    @Test
    void tiesPreferTheInHandRecipeOverTheTableOne() {
        // Same output, same bill, one needs a bench: no walk beats a walk.
        CraftRecipe sticksAtTable = new CraftRecipe("mod:sticks-at-table",
                ItemStack.of("minecraft:stick", 4, 64),
                List.of(new CraftRecipe.Ingredient(
                        Set.of("minecraft:oak_planks", "minecraft:birch_planks"), 2)), true);
        book(sticksAtTable, sticksFromPlanks());
        List<Task> plan = new CraftFor(STICKS, 4, Set.of()).decompose(ctx);
        assertEquals(2, plan.size(), "no EnsureTable in the plan: the in-hand recipe won the tie");
    }

    @Test
    void coveredBillPricesCheaperThanMissingMaterials() {
        book(planksFromLog());
        CraftFor craft = new CraftFor(PLANKS, 4, Set.of());
        assertEquals(CraftFor.MISSING_COST, craft.estimateCost(ctx), "empty pack: materials missing");
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        assertEquals(CraftFor.COVERED_COST, craft.estimateCost(ctx), "one log covers four planks");
    }

    @Test
    void decomposesToOneObtainPerBillLineThenTheCraft() {
        book(sticksFromPlanks());
        List<Task> plan = new CraftFor(STICKS, 4, Set.of()).decompose(ctx);
        assertEquals(2, plan.size());
        ObtainItem materials = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertEquals(2, materials.count(), "one craft: two planks");
        assertTrue(materials.spec().matches("minecraft:birch_planks"),
                "the line's whole alternative set carries into the sub-goal");
        CraftStep exchange = assertInstanceOf(CraftStep.class, plan.get(1));
        assertEquals(1, exchange.times());
    }

    @Test
    void shortfallScalesTheCraftsAndTheBill() {
        book(planksFromLog());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_planks", 5, 64));
        List<Task> plan = new CraftFor(PLANKS, 12, Set.of()).decompose(ctx);
        // Short 7 planks at 4 a craft -> 2 crafts -> 2 logs.
        assertEquals(2, ((ObtainItem) plan.get(0)).count());
        assertEquals(2, ((CraftStep) plan.get(1)).times());
    }

    @Test
    void thePursuedSetRefusesTheCycle() {
        book(ingotFromNuggets(), nuggetsFromIngot());
        // Obtaining ingots via nuggets: each descent adds its OUTPUT to the pursued set, so the
        // loop is refused exactly where it would close — at the sub-goal asking for ingots again.
        List<Task> plan = new CraftFor(INGOTS, 1, Set.of()).decompose(ctx);
        ObtainItem nuggets = (ObtainItem) plan.get(0);
        assertTrue(nuggets.pursued().contains("minecraft:gold_ingot"));
        CraftFor viaIngot = new CraftFor(nuggets.spec(), nuggets.count(), nuggets.pursued());
        assertTrue(viaIngot.applicable(ctx),
                "nuggets are not yet pursued — this level may still plan a craft");
        ObtainItem backToIngots = (ObtainItem) viaIngot.decompose(ctx).get(0);
        assertEquals(Set.of("minecraft:gold_ingot", "minecraft:gold_nugget"),
                backToIngots.pursued());
        assertFalse(new CraftFor(backToIngots.spec(), backToIngots.count(),
                        backToIngots.pursued()).applicable(ctx),
                "the ingot recipe's output is already pursued — the cycle dies here, bounded");
    }

    @Test
    void aSiblingObtainIsNotAnAncestor() {
        book(planksFromLog());
        // The planks sub-goal under an axe want may still craft from logs — nobody's ancestor.
        CraftFor planks = new CraftFor(PLANKS, 4, Set.of("minecraft:wooden_axe"));
        assertTrue(planks.applicable(ctx));
        ObtainItem logs = (ObtainItem) planks.decompose(ctx).get(0);
        assertEquals(Set.of("minecraft:wooden_axe", "minecraft:oak_planks"), logs.pursued(),
                "the descent grows the set by exactly the chosen recipe's output");
    }

    @Test
    void craftForIsAlwaysTheLastMethodOfAnObtain() {
        // Load-bearing for saves: a resumed plan re-finds its method BY INDEX, so CraftFor may
        // only ever sit at the end, after the scavenge method and every registered producer.
        List<Method> methods = new ObtainItem(PLANKS, 4).methods();
        assertInstanceOf(CraftFor.class, methods.get(methods.size() - 1));
    }
}
