package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.task.PutAwaySurplus;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Surplus;

/**
 * Put things down before you cannot pick anything up — layer 1's safeguard, and deliberately NOT
 * the usual way a pack gets cleared. Routine tidying is a standing project on the personal board,
 * which lands between tasks because work never preempts mid-flight; this exists for the case no
 * cadence can catch, where a single act fills the last slots and leaves a settler standing beside
 * a log they cannot lift.
 *
 * <p><b>A hand-written instinct rather than a declared need</b> (decision: Luiz, 2026-08-20): the
 * needs registry is for felt states with a gauge, and a full pack is a fact about the pack.
 *
 * <p><b>It bids on surplus, never on fullness</b> (decision: Luiz). A pack at 35 of 36 slots
 * holding nothing but kit, food and reserved materials bids zero — a settler carrying the blocks
 * for a house they are about to build is using their pack, not burdened by it. See {@link Surplus}.
 *
 * <p><b>No depot, no unburden</b> (decision: Luiz, 2026-09-30): the load goes to
 * {@link BrainContext#depot()} or nowhere, so a body with no base keeps what it carries.
 */
public final class UnburdenInstinct implements Instinct {

    /**
     * Pressure at one, two and three slots left. Above the wander floor so an idle body goes, and
     * below {@code mind.preempt} so a working one finishes the tree it is on first.
     */
    private static final double[] TIGHT = {0.55, 0.45, 0.35};

    /** No room at all: over any sane preempt bar, because the body cannot carry on at all. */
    private static final double STUCK = 0.95;

    @Override
    public double pressure(BrainContext ctx) {
        // Room first. Surplus walks every stack, and asked of every body on every tick it was 5.8%
        // of a 90-body tick (2026-09-27), for packs that had room and would have bid zero anyway.
        int empty = Surplus.emptySlots(ctx.percepts().inventory());
        // A pack with no empty slots can still absorb more of a kind it part-holds, so "0 empty"
        // is not literally "cannot lift anything" — this fires slightly early on purpose. Being
        // early costs one trip; being late strands a settler beside a log they cannot pick up.
        int slack = ctx.profile().i(ProfileAspect.UNBURDEN_SLACK_SLOTS);
        if (empty > 0 && (empty > TIGHT.length || empty > slack)) {
            return 0.0;
        }
        if (ctx.depot().isEmpty()) {
            return 0.0;
        }
        if (Surplus.slots(ctx.percepts().inventory(), ctx.reserved(),
                stack -> ctx.percepts().foods().of(stack).isPresent()).isEmpty()) {
            // Nothing to shed. Bidding here would win the wheel to run a goal that is already
            // satisfied, fail, and burn a cooldown on a body that is merely carrying a lot.
            return 0.0;
        }
        return empty == 0 ? STUCK : TIGHT[empty - 1];
    }

    @Override
    public Task root(BrainContext ctx) {
        // The arbiter grants only on a positive bid, and no depot bids 0.
        return new PutAwaySurplus(0);
    }

    @Override
    public double costTolerance(BrainContext ctx) {
        // Bounded, unlike an emergency drive's. The walk to the depot is free, so the cap only
        // decides whether building a store there (EnsureStore.PLACE_COST) is affordable.
        return ctx.profile().d(ProfileAspect.UNBURDEN_TOLERANCE);
    }

    @Override
    public Deed doing(BrainContext ctx) {
        return Deed.of(Doings.SORTING_PACK);
    }

    @Override
    public String describe() {
        return "unburden";
    }
}
