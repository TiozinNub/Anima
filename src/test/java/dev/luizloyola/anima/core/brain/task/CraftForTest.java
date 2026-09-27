package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.gate.Gate;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.Recipes;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import java.util.List;
import java.util.Optional;
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
        Producers.reset();
        Gate.install(Gate.OPEN);
    }

    private static void book(CraftRecipe... recipes) {
        List<CraftRecipe> all = List.of(recipes);
        Recipes.provide(spec -> all.stream().filter(r -> spec.matches(r.outputId())).toList());
    }

    private static CraftRecipe stoneAxeNeedingTable() {
        return new CraftRecipe("minecraft:stone_axe", ItemStack.of("minecraft:stone_axe", 1, 1),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:cobblestone"), 3),
                        new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 2)), true);
    }

    /** Refuses every stone item, the way a Wood Age answer would. */
    private void gateOutStone() {
        Gate.install(new Gate.Policy() {
            @Override
            public Optional<String> refuseItem(AgentId body, String itemId) {
                return itemId.startsWith("minecraft:stone_")
                        ? Optional.of("not in the Stone Age") : Optional.empty();
            }

            @Override
            public Optional<String> refuseAct(AgentId body, Act act) {
                return Optional.empty();
            }
        });
        ctx.gate = Gate.viewFor(ctx.self, ctx.journal());
    }

    @Test
    void aRecipeTheGateRefusesIsNeverChosen() {
        // Stone first, so the book's own order would pick it: both bills are covered, a tie.
        book(stoneAxeNeedingTable(), axeNeedingTable());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:cobblestone", 3, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_planks", 3, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:stick", 2, 64));
        ItemSpec anyAxe = ItemSpec.anyOf(Set.of("minecraft:wooden_axe", "minecraft:stone_axe"));

        List<Task> open = new CraftFor(anyAxe, 1, Set.of()).decompose(ctx);
        assertEquals("minecraft:stone_axe",
                ((CraftStep) open.get(open.size() - 1)).recipe().outputId(), "ungated, the book decides");

        gateOutStone();
        List<Task> gated = new CraftFor(anyAxe, 1, Set.of()).decompose(ctx);
        assertEquals("minecraft:wooden_axe",
                ((CraftStep) gated.get(gated.size() - 1)).recipe().outputId(),
                "cobblestone in the pack does not make a stone axe in the Wood Age");
    }

    @Test
    void aRefusedRecipeIsNotAWayToReachAnything() {
        book(stoneAxeNeedingTable());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:cobblestone", 3, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:stick", 2, 64));
        ItemSpec stoneAxe = ItemSpec.anyOf(Set.of("minecraft:stone_axe"));
        assertTrue(CraftFor.anyReachable(stoneAxe, ctx));
        gateOutStone();
        assertFalse(CraftFor.anyReachable(stoneAxe, ctx),
                "the board's question gets the same answer, so no errand is claimed toward it");
    }

    private static CraftRecipe planks(String wood) {
        return new CraftRecipe("minecraft:" + wood + "_planks",
                ItemStack.of("minecraft:" + wood + "_planks", 4, 64),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:" + wood + "_log"), 1)), false);
    }

    /** A way a consumer registered: reachability asks that it exists, "now" that it applies. */
    private record Way(boolean applies) implements Method {
        @Override
        public boolean applicable(BrainContext c) {
            return applies;
        }

        @Override
        public double estimateCost(BrainContext c) {
            return 1.0;
        }

        @Override
        public List<Task> decompose(BrainContext c) {
            return List.of();
        }

        @Override
        public String describe() {
            return "a registered way";
        }
    }

    private static final ItemSpec LOGS =
            ItemSpec.register(new ItemSpec("craft-test-logs", id -> id.endsWith("_log")));
    private static final ItemSpec COBBLE = ItemSpec.register(
            new ItemSpec("craft-test-cobble", id -> id.equals("minecraft:cobblestone")));
    private static final ItemSpec FUEL =
            ItemSpec.register(new ItemSpec("craft-test-fuel", id -> id.endsWith("coal")));

    private static final ItemSpec THREE_PLANKS = ItemSpec.anyOf(Set.of("minecraft:acacia_planks",
            "minecraft:birch_planks", "minecraft:oak_planks"));

    @Test
    void anEmptyPackLeavesTheSpeciesToTheObtain() {
        // Birch is earlier in the book than oak: choosing the recipe first sent an oak wood's
        // settlers to far birches (in-world, 2026-09-27). The obtain carries every log instead.
        book(planks("acacia"), planks("birch"), planks("oak"));
        Producers.register(LOGS, wanted -> new Way(true));

        List<Task> plan = new CraftFor(THREE_PLANKS, 4, Set.of()).decompose(ctx);

        assertEquals(1, plan.size(), "no craft yet: which one waits on the log that comes");
        ObtainItem logs = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertEquals(1, logs.count());
        for (String wood : List.of("acacia", "birch", "oak")) {
            assertTrue(logs.spec().matches("minecraft:" + wood + "_log"), wood);
            assertTrue(logs.pursued().contains("minecraft:" + wood + "_planks"), wood);
        }

        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        List<Task> next = new CraftFor(THREE_PLANKS, 4, Set.of()).decompose(ctx);
        assertEquals("minecraft:oak_planks",
                ((CraftStep) next.get(next.size() - 1)).recipe().outputId(),
                "the next round makes what the log that came makes");
    }

    @Test
    void theRecipeThePackRunsMostOfGoesFirst() {
        book(planks("birch"), planks("oak"));
        Producers.register(LOGS, wanted -> new Way(true));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:birch_log", 1, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 2, 64));

        List<Task> plan = new CraftFor(THREE_PLANKS, 12, Set.of()).decompose(ctx);

        CraftStep step = (CraftStep) plan.get(plan.size() - 1);
        assertEquals("minecraft:oak_planks", step.recipe().outputId());
        assertEquals(2, step.times(), "both oak logs now; the birch log is the next round's");
    }

    private static CraftRecipe torch(String fuel) {
        return new CraftRecipe(fuel + "-torch", ItemStack.of("minecraft:torch", 4, 64),
                List.of(new CraftRecipe.Ingredient(Set.of(fuel), 1),
                        new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 1)), false);
    }

    @Test
    void aSharedLineIsGatheredBesideTheOpenOne() {
        book(torch("minecraft:coal"), torch("minecraft:charcoal"));
        Producers.register(FUEL, wanted -> new Way(true));
        Producers.register(STICKS, wanted -> new Way(true));

        List<Task> plan = new CraftFor(ItemSpec.anyOf(Set.of("minecraft:torch")), 8, Set.of())
                .decompose(ctx);

        assertEquals(2, plan.size());
        ObtainItem sticks = assertInstanceOf(ObtainItem.class, plan.get(0));
        assertTrue(sticks.spec().matches("minecraft:stick"));
        assertEquals(2, sticks.count(), "two runs of four torches");
        ObtainItem fuel = assertInstanceOf(ObtainItem.class, plan.get(1));
        assertTrue(fuel.spec().matches("minecraft:coal") && fuel.spec().matches("minecraft:charcoal"));
        assertEquals(2, fuel.count());
    }

    @Test
    void billsThatCannotMixStillGoToTheOneThatCanBeHadNow() {
        // Three planks or three cobblestone: gathered as a mix they make no axe, so these do not
        // merge. Stone is first in the book, but no stone is known and a tree is.
        book(stoneAxeNeedingTable(), axeNeedingTable(), planksFromLog());
        Producers.register(LOGS, wanted -> new Way(true));
        Producers.register(COBBLE, wanted -> new Way(false));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:stick", 2, 64));
        ItemSpec anyAxe = ItemSpec.anyOf(Set.of("minecraft:wooden_axe", "minecraft:stone_axe"));

        List<Task> plan = new CraftFor(anyAxe, 1, Set.of()).decompose(ctx);

        assertEquals("minecraft:wooden_axe",
                ((CraftStep) plan.get(plan.size() - 1)).recipe().outputId());
    }

    @Test
    void anUnopenedChestIsNotStoneThatCanBeHadNow() {
        // As above, with one of the party's chests nearby that nobody has opened. Worth a look,
        // but no evidence of stone: counted as a way, both bills would tie and stone would win.
        book(stoneAxeNeedingTable(), axeNeedingTable(), planksFromLog());
        Producers.register(LOGS, wanted -> new Way(true));
        Producers.register(COBBLE, wanted -> new Way(false));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:stick", 2, 64));
        dev.luizloyola.anima.core.social.Places places = new dev.luizloyola.anima.core.social.Places();
        dev.luizloyola.anima.core.social.PartyId party = dev.luizloyola.anima.core.social.PartyId.random();
        places.asks(new dev.luizloyola.anima.core.social.Places.Parties() {
            @Override
            public Optional<dev.luizloyola.anima.core.social.PartyId> current(AgentId who) {
                return Optional.of(party);
            }

            @Override
            public dev.luizloyola.anima.core.social.PartyId of(AgentId who) {
                return party;
            }
        });
        ctx.knowledge.sees(places.viewFor(ctx.self), () -> ctx.percepts.time);
        places.viewFor(ctx.self).foundCommunal(dev.luizloyola.anima.core.store.Store.POI,
                new dev.luizloyola.anima.core.brain.sense.Pos(4, 64, 0), 0L);
        ItemSpec anyAxe = ItemSpec.anyOf(Set.of("minecraft:wooden_axe", "minecraft:stone_axe"));

        List<Task> plan = new CraftFor(anyAxe, 1, Set.of()).decompose(ctx);

        assertEquals("minecraft:wooden_axe",
                ((CraftStep) plan.get(plan.size() - 1)).recipe().outputId());
    }

    @Test
    void notApplicableWithoutAnyRecipe() {
        assertFalse(new CraftFor(PLANKS, 4, Set.of()).applicable(ctx), "empty book");
    }

    @Test
    void aTableRecipePlansItsBenchBetweenTheBillAndTheExchange() {
        book(axeNeedingTable());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_planks", 3, 64));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:stick", 2, 64));
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
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_planks", 2, 64));
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
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_planks", 2, 64));
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
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 2, 64));
        List<Task> plan = new CraftFor(PLANKS, 12, Set.of()).decompose(ctx);
        // Short 7 planks at 4 a craft -> 2 crafts -> 2 logs.
        assertEquals(2, ((ObtainItem) plan.get(0)).count());
        assertEquals(2, ((CraftStep) plan.get(1)).times());
    }

    @Test
    void aPureCycleIsNotEvenApplicable() {
        // Ingots from nuggets from ingots, nothing real at the bottom: reachability walks the
        // loop with the occurs-check's own output-id guard and finds no floor.
        book(ingotFromNuggets(), nuggetsFromIngot());
        assertFalse(new CraftFor(INGOTS, 1, Set.of()).applicable(ctx));
    }

    @Test
    void thePursuedSetRefusesTheCycleWhereItWouldClose() {
        book(ingotFromNuggets(), nuggetsFromIngot());
        // With real nuggets in the pack the ingot craft has a floor and runs…
        ctx.percepts.inventory.add(ItemStack.of("minecraft:gold_nugget", 9, 64));
        List<Task> plan = new CraftFor(INGOTS, 1, Set.of()).decompose(ctx);
        ObtainItem nuggets = (ObtainItem) plan.get(0);
        assertTrue(nuggets.pursued().contains("minecraft:gold_ingot"));
        // …but the nugget sub-goal must not crawl back up: its only recipe consumes the very
        // ingot being pursued, which reachability now rejects a level earlier than the
        // decompose-time check alone used to.
        assertFalse(new CraftFor(nuggets.spec(), nuggets.count(), nuggets.pursued())
                        .applicable(ctx),
                "the way back up is the pursued ingot — refused before any plan starts");
    }

    @Test
    void aSiblingObtainIsNotAnAncestor() {
        book(planksFromLog());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 1, 64));
        // The planks sub-goal under an axe want may still craft from logs — nobody's ancestor.
        CraftFor planks = new CraftFor(PLANKS, 4, Set.of("minecraft:wooden_axe"));
        assertTrue(planks.applicable(ctx));
        ObtainItem logs = (ObtainItem) planks.decompose(ctx).get(0);
        assertEquals(Set.of("minecraft:wooden_axe", "minecraft:oak_planks"), logs.pursued(),
                "the descent grows the set by exactly the chosen recipe's output");
    }

    @Test
    void aReachableRecipeBeatsAnEarlierUnreachableOne() {
        // The bug this filter exists for: "any axe" listed the copper axe first, ties broke by
        // order, and a round gives one method attempt — so settlers with a forest at their back
        // shrugged the want off.
        CraftRecipe copperAxe = new CraftRecipe("minecraft:copper_axe",
                ItemStack.of("minecraft:copper_axe", 1, 1),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:copper_ingot"), 3),
                        new CraftRecipe.Ingredient(Set.of("minecraft:stick"), 2)), true);
        book(copperAxe, axeNeedingTable(), planksFromLog(), sticksFromPlanks());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:oak_log", 2, 64));

        ItemSpec axes = ItemSpec.register(
                new ItemSpec("craft-test-axes-family", id -> id.endsWith("_axe")));
        List<Task> plan = new CraftFor(axes, 1, Set.of()).decompose(ctx);
        CraftStep exchange = (CraftStep) plan.get(plan.size() - 1);
        assertEquals("minecraft:wooden_axe", exchange.recipe().outputId(),
                "two logs and a book: the wooden axe is the one with a floor under it");
    }

    /** Vanilla's {@code #birch_logs}: what one birch-planks craft accepts. */
    private static final Set<String> BIRCH_LOGS = Set.of("minecraft:birch_log",
            "minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood");

    @Test
    void barkIsNoWayToTheLogsItIsMadeOf() {
        book(new CraftRecipe("minecraft:birch_wood", ItemStack.of("minecraft:birch_wood", 3, 64),
                List.of(new CraftRecipe.Ingredient(Set.of("minecraft:birch_log"), 4)), false));
        Producers.register(LOGS, wanted -> new Way(false)); // the chop whose tree just failed
        ItemSpec planksLine = ItemSpec.anyOf(BIRCH_LOGS);

        assertFalse(new CraftFor(planksLine, 1, Set.of("minecraft:birch_planks")).applicable(ctx),
                "a failed chop falls through to the next way, and bark must not be it");
        assertFalse(CraftFor.anyReachable(planksLine, ctx));
        ctx.percepts.inventory.add(ItemStack.of("minecraft:birch_log", 4, 64));
        assertFalse(new CraftFor(planksLine, 5, Set.of("minecraft:birch_planks")).applicable(ctx),
                "four logs cover the bark bill, and leave three of the five wanted, not more");
    }

    @Test
    void aRecipeThatMakesMoreThanItEatsStillCounts() {
        book(nuggetsFromIngot());
        ctx.percepts.inventory.add(ItemStack.of("minecraft:gold_ingot", 1, 64));
        ItemSpec gold = ItemSpec.anyOf(Set.of("minecraft:gold_ingot", "minecraft:gold_nugget"));
        assertTrue(new CraftFor(gold, 5, Set.of()).applicable(ctx), "one ingot in, nine nuggets out");
    }

    @Test
    void craftForIsImmediatelyFollowedByTakeFromStore() {
        // This pins only the RELATIVE order of the tail pair, so it survives future appends past
        // TakeFromStore without renaming. It is NOT what protects a saved plan: it stays green
        // even if a defect wrongly inserted a new way ahead of CraftFor instead of appending one,
        // since that still leaves CraftFor directly followed by TakeFromStore. The absolute-index
        // guarantee a saved plan actually depends on is pinned in ObtainItemTest's
        // aLiteralIngredientReachesTheConsumersProducerByContent.
        List<Method> methods = new ObtainItem(PLANKS, 4).methods();
        assertInstanceOf(CraftFor.class, methods.get(methods.size() - 2));
        assertInstanceOf(TakeFromStore.class, methods.get(methods.size() - 1));
    }
}
