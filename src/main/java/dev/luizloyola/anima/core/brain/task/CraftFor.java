package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.craft.CraftRecipe;
import dev.luizloyola.anima.core.craft.Recipes;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The making method — the third way {@link ObtainItem} can have a thing, after picking one up and
 * after a consumer's registered producers. Applicable exactly when a registered
 * {@link Recipes recipe source} knows an in-hand recipe for the spec; recipes that need a table are
 * filtered out until the table era.
 *
 * <p>Decomposition: one {@link ObtainItem} per bill line (satisfied-check-first, so stocked
 * materials cost nothing), then a {@link CraftStep}. Sub-goal specs are
 * {@link ItemSpec#anyOf literal} — "any plank", straight from the ingredient — so they persist by
 * content and a reload rebuilds them without any mod having declared a planks class.
 *
 * <p><b>The occurs-check is ancestor-based</b>, carried as {@code pursued}: the output ids this
 * branch of the goal stack is already obtaining. A recipe whose output is already pursued is not
 * offered (the gold-ingot ⇄ gold-nugget cycle refusing to loop) while a SIBLING obtain of the
 * same item is untouched, so an empty-handed settler may chop one log for planks on the way to an
 * axe whose errand also wants logs. A satisfied goal never expands, so only a genuine cycle bites.
 */
public final class CraftFor implements Method {

    /**
     * Cost when the pack already covers the whole bill — cheaper than walking anywhere that is
     * not next to your feet, pricier than drops already in reach. On the same blocks-flavoured
     * scale every other method prices in.
     */
    public static final double COVERED_COST = 2.0;

    /**
     * Cost when materials would have to be obtained first — the errand of unknown length. High
     * enough that a remembered producer or a visible drop pile wins first; low enough that a body
     * with no better way still crafts rather than fails.
     */
    public static final double MISSING_COST = 24.0;

    private final ItemSpec spec;
    private final int count;
    private final Set<String> pursued;

    public CraftFor(ItemSpec spec, int count, Set<String> pursued) {
        this.spec = spec;
        this.count = count;
        this.pursued = Set.copyOf(pursued);
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return !usable().isEmpty();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        for (CraftRecipe recipe : usable()) {
            if (coverable(recipe, craftsNeeded(recipe, pack), pack)) {
                return COVERED_COST;
            }
        }
        return MISSING_COST;
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        CraftRecipe recipe = pick(pack);
        int crafts = craftsNeeded(recipe, pack);
        Set<String> nowPursued = new HashSet<>(pursued);
        nowPursued.add(recipe.outputId());
        List<Task> plan = new ArrayList<>();
        // One obtain per bill line, for the whole run of crafts — each is satisfied-check-first,
        // so a line the pack covers is a free pass rather than an errand.
        for (CraftRecipe.Ingredient line : recipe.ingredients()) {
            plan.add(new ObtainItem(ItemSpec.anyOf(line.acceptedIds()),
                    line.count() * crafts, nowPursued));
        }
        plan.add(new CraftStep(recipe, crafts));
        return plan;
    }

    @Override
    public String describe() {
        return "craft " + spec.name();
    }

    /** Recipes this method may use: in-hand, producing the spec, and not already being pursued. */
    private List<CraftRecipe> usable() {
        List<CraftRecipe> fit = new ArrayList<>();
        for (CraftRecipe recipe : Recipes.producing(spec)) {
            if (!recipe.needsTable() && !pursued.contains(recipe.outputId())) {
                fit.add(recipe);
            }
        }
        return fit;
    }

    /**
     * The recipe to expand: covered-from-the-pack beats everything, then the fewest missing
     * items, then source order — so the same pack always plans the same craft.
     */
    private CraftRecipe pick(Inventory pack) {
        CraftRecipe best = null;
        int bestMissing = Integer.MAX_VALUE;
        for (CraftRecipe recipe : usable()) {
            int missing = missingFor(recipe, craftsNeeded(recipe, pack), pack);
            if (missing < bestMissing) {
                best = recipe;
                bestMissing = missing;
            }
        }
        return best;
    }

    /** How many crafts close the shortfall between the pack and the wanted count. */
    private int craftsNeeded(CraftRecipe recipe, Inventory pack) {
        int shortfall = Math.max(1, count - pack.count(spec.matcher()));
        return (shortfall + recipe.outputCount() - 1) / recipe.outputCount();
    }

    private static boolean coverable(CraftRecipe recipe, int crafts, Inventory pack) {
        return missingFor(recipe, crafts, pack) == 0;
    }

    /** Items the pack is short of for {@code crafts} runs of this recipe, summed over the bill. */
    private static int missingFor(CraftRecipe recipe, int crafts, Inventory pack) {
        int missing = 0;
        for (CraftRecipe.Ingredient line : recipe.ingredients()) {
            int needed = line.count() * crafts;
            int held = pack.count(line.acceptedIds()::contains);
            missing += Math.max(0, needed - held);
        }
        return missing;
    }
}
