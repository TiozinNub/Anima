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
 * The making method — the third way {@link ObtainItem} can have a thing, after picking one up
 * and after a registered producer. Applicable when a registered {@link Recipes recipe source}
 * knows a recipe for the spec.
 *
 * <p>An in-hand recipe crafts where the body stands; one needing a table gets an
 * {@link EnsureTable} after the materials and before the exchange, and ties prefer the in-hand
 * shape — no walk is cheaper. Decomposition: one {@link ObtainItem} per bill line (each
 * satisfied-check-first, so stocked materials cost nothing), then a {@link CraftStep}. Sub-goal
 * specs are {@link ItemSpec#anyOf literal} — "any plank", straight from the ingredient — so they
 * persist by content and a reload rebuilds them with no mod having declared a planks class.
 *
 * <p><b>The occurs-check is ancestor-based</b>, carried as {@code pursued}: the output ids this
 * branch of the goal stack is already obtaining. A recipe whose output is already pursued is not
 * offered (the gold-ingot ⇄ gold-nugget cycle refusing to loop) while a SIBLING obtain of the
 * same item is untouched, letting an empty-handed settler chop one log for planks on the way to
 * an axe whose errand also wants logs. A satisfied goal never expands, so only a genuine cycle
 * is bitten.
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
        return !usable(ctx).isEmpty();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        for (CraftRecipe recipe : usable(ctx)) {
            if (coverable(recipe, craftsNeeded(recipe, pack), pack)) {
                return COVERED_COST;
            }
        }
        return MISSING_COST;
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        CraftRecipe recipe = pick(ctx);
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
        if (recipe.needsTable()) {
            // Materials, bench, then the bill again: making the bench may CONSUME the bill (the
            // table itself is planks). Every obtain is satisfied-check-first, so an untouched
            // pack tops up for zero and an eaten one is re-gathered before the exchange instead
            // of failing it — gather 4 planks, craft the table from them, arrive 2 short.
            plan.add(new EnsureTable(nowPursued));
            for (CraftRecipe.Ingredient line : recipe.ingredients()) {
                plan.add(new ObtainItem(ItemSpec.anyOf(line.acceptedIds()),
                        line.count() * crafts, nowPursued));
            }
        }
        plan.add(new CraftStep(recipe, crafts));
        return plan;
    }

    @Override
    public String describe() {
        return "craft " + spec.name();
    }

    /**
     * Recipes this method may use: producing the spec, not already pursued, allowed this body by
     * the gate, and <b>reachable</b> — every bill line must have some way to be had from here.
     * Without that filter "any axe"
     * resolved to the book's first entry (the copper axe) and, one method attempt per round, a
     * settler with a forest at their back shrugged the want off over copper ingots.
     */
    private List<CraftRecipe> usable(BrainContext ctx) {
        List<CraftRecipe> fit = new ArrayList<>();
        for (CraftRecipe recipe : Recipes.producing(spec)) {
            if (pursued.contains(recipe.outputId()) || !ctx.gate().mayMake(recipe.outputId())) {
                continue;
            }
            Set<String> guard = new HashSet<>(pursued);
            guard.add(recipe.outputId());
            if (reachable(recipe, guard, ctx, REACH_DEPTH)) {
                fit.add(recipe);
            }
        }
        return fit;
    }

    /**
     * Whether any registered recipe for {@code spec} is reachable from where this body stands —
     * the board's gate asks this too, so an errand is only ever claimed toward a craft some plan
     * can actually finish.
     */
    public static boolean anyReachable(ItemSpec spec, BrainContext ctx) {
        for (CraftRecipe recipe : Recipes.producing(spec)) {
            if (!ctx.gate().mayMake(recipe.outputId())) {
                continue;
            }
            Set<String> guard = new HashSet<>();
            guard.add(recipe.outputId());
            if (reachable(recipe, guard, ctx, REACH_DEPTH)) {
                return true;
            }
        }
        return false;
    }

    /** Recursion bound for pathological (modded) books; vanilla chains are three deep at most. */
    private static final int REACH_DEPTH = 8;

    private static boolean reachable(CraftRecipe recipe, Set<String> guard, BrainContext ctx,
                                     int depth) {
        if (depth <= 0) {
            return false;
        }
        for (CraftRecipe.Ingredient line : recipe.ingredients()) {
            if (!lineReachable(line, guard, ctx, depth)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One bill line's ways, cheapest question first: already in the pack, a producer somebody
     * registered (the chop), lying in sight as a drop, or craftable by a recipe that is itself
     * reachable — guarded by the same output-id set the occurs-check uses, so a cyclic book
     * answers "no" instead of hanging.
     */
    private static boolean lineReachable(CraftRecipe.Ingredient line, Set<String> guard,
                                         BrainContext ctx, int depth) {
        if (ctx.percepts().inventory().count(line.acceptedIds()::contains) >= line.count()) {
            return true;
        }
        if (Producers.knowsAnyOf(line.acceptedIds())) {
            return true;
        }
        for (dev.luizloyola.anima.core.brain.sense.Drop drop : ctx.percepts().drops()) {
            if (line.accepts(drop.itemId())) {
                return true;
            }
        }
        // Deliberately UNREGISTERED: a throwaway lens for one question, not a name to persist.
        ItemSpec lineSpec = new ItemSpec("(reachable?)", line.acceptedIds()::contains);
        for (CraftRecipe making : Recipes.producing(lineSpec)) {
            if (guard.contains(making.outputId()) || !ctx.gate().mayMake(making.outputId())) {
                continue;
            }
            Set<String> deeper = new HashSet<>(guard);
            deeper.add(making.outputId());
            if (reachable(making, deeper, ctx, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The recipe to expand: covered-from-the-pack beats everything, then one whose shortfall can be
     * had right now, then the fewest missing items, then in-hand over table (no walk beats a walk),
     * then source order — so the same pack always plans the same craft.
     *
     * <p>"Right now" is what stopped an empty-handed settler in an oak forest choosing acacia
     * planks: every plank recipe is one log short, the book's first is acacia, and no tree it knew
     * could give an acacia log (in-world, 2026-09-24). Reachability still only asks whether a way
     * could exist; this asks which one does.
     */
    private CraftRecipe pick(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        CraftRecipe best = null;
        int bestMissing = Integer.MAX_VALUE;
        boolean bestNow = false;
        for (CraftRecipe recipe : usable(ctx)) {
            int crafts = craftsNeeded(recipe, pack);
            int missing = missingFor(recipe, crafts, pack);
            boolean now = missing == 0 || shortfallHadNow(recipe, crafts, ctx, NOW_DEPTH);
            boolean wins = best == null
                    || (now && !bestNow)
                    || (now == bestNow && (missing < bestMissing
                            || (missing == bestMissing && best.needsTable() && !recipe.needsTable())));
            if (wins) {
                best = recipe;
                bestMissing = missing;
                bestNow = now;
            }
        }
        return best;
    }

    /** How deep "can be had now" follows a craft: planks from logs is one level. */
    private static final int NOW_DEPTH = 2;

    /** Whether every line the pack is short of has a way that applies at this moment. */
    private static boolean shortfallHadNow(CraftRecipe recipe, int crafts, BrainContext ctx,
                                           int depth) {
        Inventory pack = ctx.percepts().inventory();
        for (CraftRecipe.Ingredient line : recipe.ingredients()) {
            if (pack.count(line.acceptedIds()::contains) >= line.count() * crafts) {
                continue;
            }
            if (!lineHadNow(line, ctx, depth)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One short line's ways, asked whether they apply now rather than whether they exist: a drop in
     * sight, a producer that can act (a remembered tree of the right species), a store known to hold
     * it, or a recipe whose own shortfall can be had now.
     */
    private static boolean lineHadNow(CraftRecipe.Ingredient line, BrainContext ctx, int depth) {
        for (dev.luizloyola.anima.core.brain.sense.Drop drop : ctx.percepts().drops()) {
            if (line.accepts(drop.itemId())) {
                return true;
            }
        }
        ItemSpec lineSpec = ItemSpec.anyOf(line.acceptedIds());
        for (Method way : Producers.forItems(line.acceptedIds(), lineSpec)) {
            if (way.applicable(ctx)) {
                return true;
            }
        }
        if (new TakeFromStore(lineSpec, line.count()).applicable(ctx)) {
            return true;
        }
        if (depth <= 0) {
            return false;
        }
        for (CraftRecipe making : Recipes.producing(lineSpec)) {
            if (ctx.gate().mayMake(making.outputId())
                    && shortfallHadNow(making, 1, ctx, depth - 1)) {
                return true;
            }
        }
        return false;
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
