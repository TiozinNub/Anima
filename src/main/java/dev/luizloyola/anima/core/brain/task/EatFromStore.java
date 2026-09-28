package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Set;

/**
 * Tier 3 of {@link SatisfyHunger}: fetch a meal of ready food from one of the party's stores, then
 * eat — the first bite here, the rest from the pack as hunger asks again.
 * Priced by the walk, as {@link TakeFromStore} prices it, so food in hand always wins, and the
 * arbiter's tolerance decides how far hunger will walk. Raw meat in hand keeps its own price.
 *
 * <p>The fetch is a stores-only {@link ObtainItem}, an achieve goal: a chest opened and found
 * without food re-scores the stores at once instead of failing the meal.
 */
public final class EatFromStore implements Method {

    /** What one piece is taken to be worth when nothing ready has been seen in store: a berry. */
    static final int BITE = 2;

    private final TakeFromStore take = new TakeFromStore(ReadyFood.SPEC, 1);

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.hasRoom(ctx) && take.applicable(ctx);
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return take.estimateCost(ctx);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return List.of(
                new ObtainItem(ReadyFood.SPEC, mealSize(ctx), Set.of(), ObtainItem.Sources.STORES),
                new EatCarried());
    }

    @Override
    public String describe() {
        return "eat from a store";
    }

    /**
     * How much to take for one meal: enough to fill the bar at the richest ready food this body
     * has seen in the party's stores, or {@link #BITE} a piece. One opening feeds a meal — a berry
     * per trip had a settler open the chest six times — and the richest measure means it never
     * carries off more than a full bar's worth.
     */
    static int mealSize(BrainContext ctx) {
        int missing = Metabolism.MAX_FOOD - ctx.percepts().metabolism().foodLevel();
        FoodLookup foods = ctx.percepts().foods();
        int richest = 0;
        for (PoiMemory store : Store.ours(ctx)) {
            for (ItemStack stack : ctx.knowledge().insideOf(store.anchor())
                    .map(seen -> seen.stacks()).orElse(List.of())) {
                if (ReadyFood.isReady(foods, stack)) {
                    richest = Math.max(richest, foods.of(stack).map(FoodValue::nutrition).orElse(0));
                }
            }
        }
        int each = richest > 0 ? richest : BITE;
        return Math.max(1, (missing + each - 1) / each);
    }
}
