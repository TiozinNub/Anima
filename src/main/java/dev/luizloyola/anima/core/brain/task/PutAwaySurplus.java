package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.Surplus;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;

/**
 * BE rid of what nobody spoke for — the one goal both halves of the stow arc run, so the acute
 * instinct and the standing project cannot drift apart in what they actually do. What differs
 * between them is only what makes them bid.
 *
 * <p>Being an achieve-goal is what makes a SECOND chest happen without a rule saying so: a round
 * that fills the first one leaves the pack still holding cargo, the goal is still unsatisfied, and
 * the next round re-scores — finding the full store avoided, walking to another, or building one.
 */
public final class PutAwaySurplus implements AchieveTask {

    private final List<Method> methods = List.of(new StowAtAStore());

    @Override
    public boolean satisfied(BrainContext ctx) {
        return Surplus.slots(ctx.percepts().inventory(), ctx.reserved(),
                stack -> ctx.percepts().foods().of(stack).isPresent()).isEmpty();
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "put away what nobody wants";
    }

    /** Get to a store, then empty the pack into it. */
    private static final class StowAtAStore implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            // EnsureStore's own two methods decide whether that means walking or building, and
            // one of them is always available to a body that is not bricked in.
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return Store.nearestKnown(ctx)
                    .map(known -> Store.distance(known.anchor(), ctx.percepts().position()))
                    .orElse(EnsureStore.PLACE_COST);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new EnsureStore(), PutItems.stow());
        }

        @Override
        public String describe() {
            return "stow it at a store";
        }
    }
}
