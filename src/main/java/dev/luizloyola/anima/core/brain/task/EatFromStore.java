package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;
import java.util.Set;

/**
 * Tier 3 of {@link SatisfyHunger}: fetch ready food from one of the party's stores, then eat it.
 * Priced by the walk, as {@link TakeFromStore} prices it, so food in hand always wins, and the
 * arbiter's tolerance decides how far hunger will walk. Raw meat in hand keeps its own price.
 *
 * <p>The fetch is a stores-only {@link ObtainItem}, an achieve goal: a chest opened and found
 * without food re-scores the stores at once instead of failing the meal.
 */
public final class EatFromStore implements Method {

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
                new ObtainItem(ReadyFood.SPEC, 1, Set.of(), ObtainItem.Sources.STORES),
                new EatCarried());
    }

    @Override
    public String describe() {
        return "eat from a store";
    }
}
