package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tier 4 of {@link SatisfyHunger}: get ready food some way other than a store — a drop in sight, or
 * whatever a consumer registered as producing it (Autarkia's forage) — then eat it. Priced by the
 * cheapest of those ways, walk and work, so food in hand or in a store wins wherever there is any,
 * and the hunger level's tolerance decides how far a meal is worth going for.
 */
public final class EatObtained implements Method {

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.hasRoom(ctx) && cheapest(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return cheapest(ctx).orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return List.of(obtain(), new EatCarried());
    }

    @Override
    public String describe() {
        return "obtain food and eat it";
    }

    private static ObtainItem obtain() {
        // Not from stores: that way is EatFromStore's, priced by its own rule.
        return new ObtainItem(ReadyFood.SPEC, 1, Set.of(), ObtainItem.Sources.NOT_STORES);
    }

    private static Optional<Double> cheapest(BrainContext ctx) {
        Double best = null;
        for (Method way : obtain().methods()) {
            if (way.applicable(ctx)) {
                double cost = way.estimateCost(ctx);
                if (best == null || cost < best) {
                    best = cost;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
