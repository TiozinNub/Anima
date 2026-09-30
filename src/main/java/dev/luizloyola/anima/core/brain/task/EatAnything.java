package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tier 5 of {@link SatisfyHunger}, for a starving body: get any food at all — raw meat from a hunt
 * or a store, not only ready food — then eat it, raw if that is what came. Priced at the cheapest
 * way plus {@link #LAST_RESORT}, so ready food wins wherever there is any and a merely hungry body
 * never pays it. Hunting for the party's stores is the food line's; this is the last case.
 */
public final class EatAnything implements Method {

    /** A last resort's price, {@link EatLastResort#TREAT_COST}: over a hungry body's tolerance of 60. */
    public static final double LAST_RESORT = EatLastResort.TREAT_COST;

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.hasRoom(ctx) && cheapest(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return cheapest(ctx).map(cost -> cost + LAST_RESORT).orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return List.of(obtain(), new EatCarried());
    }

    @Override
    public String describe() {
        return "get any food and eat it";
    }

    private static ObtainItem obtain() {
        return new ObtainItem(Food.SPEC, 1, Set.of(), ObtainItem.Sources.ANY);
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
