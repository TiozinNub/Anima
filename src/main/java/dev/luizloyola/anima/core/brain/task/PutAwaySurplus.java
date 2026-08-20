package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Surplus;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

    /** Where the load is wanted, or null for "the nearest store" — see {@link EnsureStore}. */
    private final @Nullable Pos hint;

    /**
     * Cargo slots that make the walk worth taking. One for the standing stow, which hauls any cargo
     * at all; a project hauling to a named yard sets it higher, so a settler fells several trees
     * between trips instead of commuting after each one.
     */
    private final int haulLine;

    private final List<Method> methods = List.of(new StowAtAStore());

    public PutAwaySurplus() {
        this(null, 1);
    }

    public PutAwaySurplus(@Nullable Pos hint, int haulLine) {
        this.hint = hint;
        this.haulLine = Math.max(1, haulLine);
    }

    /** The yard this goal is feeding, for the codec; null for the nearest-store flavour. */
    public @Nullable Pos hint() {
        return hint;
    }

    public int haulLine() {
        return haulLine;
    }

    /**
     * Below the line there is nothing to do — which is what makes "haul when laden" fall out of the
     * achieve-loop re-asking, rather than anything scheduling it: a settler four slots into a box of
     * trees is already satisfied and simply takes the next one.
     */
    @Override
    public boolean satisfied(BrainContext ctx) {
        return Surplus.slots(ctx.percepts().inventory(), ctx.reserved(),
                stack -> ctx.percepts().foods().of(stack).isPresent()).size() < haulLine;
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
    private final class StowAtAStore implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            // EnsureStore's own two methods decide whether that means walking or building, and
            // one of them is always available to a body that is not bricked in.
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            if (hint != null) {
                // Priced at the yard, not at the nearest chest: this errand is already claimed,
                // so the number only decides walk-versus-build inside it.
                return Store.distance(hint, here);
            }
            return Store.nearestKnown(ctx)
                    .map(known -> Store.distance(known.anchor(), here))
                    .orElse(EnsureStore.PLACE_COST);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new EnsureStore(hint), PutItems.stow());
        }

        @Override
        public String describe() {
            return "stow it at a store";
        }
    }
}
