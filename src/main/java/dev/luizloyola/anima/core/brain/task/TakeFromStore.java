package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.Seen;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Optional;

/**
 * Take it out of one of the party's stores ({@link Store#ours}) — the cheapest way to have a thing
 * when a party has been putting things away.
 *
 * <p>A store this body looked into and saw the thing in costs its distance plus the look's age. One
 * it never opened costs {@link #UNOPENED_FACTOR} × its distance: opening it may end the trip sooner,
 * and what is inside is worth knowing next time. So a known store 10 blocks off loses to an
 * unopened one 5 off, and wins at the same distance (decision 14,
 * {@code 2026-09-24-directions-design.md}). Age never prices a look above not having looked. A look
 * that found none rules the store out for {@code stores.recheck_ticks}; after that somebody may have
 * filled it, and it is as good as unopened.
 */
public final class TakeFromStore implements Method {

    /** Luiz, 2026-09-27. Anything strictly between 1 and 2 keeps both of decision 14's cases. */
    static final double UNOPENED_FACTOR = 1.5;

    private final ItemSpec spec;
    private final int count;
    private final boolean allowed;

    public TakeFromStore(ItemSpec spec, int count) {
        this(spec, count, true);
    }

    public TakeFromStore(ItemSpec spec, int count, boolean allowed) {
        this.spec = spec;
        this.count = count;
        this.allowed = allowed;
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return bestStore(ctx).isPresent();
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        return bestStore(ctx).map(Candidate::cost).orElse(Double.MAX_VALUE);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Candidate best = bestStore(ctx).orElseThrow();
        // Shared with EnsureTable.WalkToKnown rather than a second copy of the same shape.
        Pos beside = EnsureTable.WalkToKnown.standableBeside(best.at(), ctx);
        return List.of(
                new GoTo(beside.x(), beside.y(), beside.z()),
                new TakeItems(best.at(), spec, count, !best.seenHolding()));
    }

    @Override
    public String describe() {
        return "take " + spec.name() + " from a store";
    }

    /**
     * Whether one of the party's stores was seen holding {@code spec}. A store worth a look is not
     * evidence the thing exists, so "can it be had now" asks this rather than {@link #applicable},
     * or every recipe would look reachable while an unopened chest stood nearby.
     */
    public static boolean seenHolding(BrainContext ctx, ItemSpec spec) {
        long now = ctx.percepts().time();
        for (PoiMemory store : Store.ours(ctx)) {
            if (!ctx.knowledge().isAvoided(Store.POI, store.anchor(), now)
                    && ctx.knowledge().insideOf(store.anchor()).map(seen -> seen.count(spec) > 0)
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Candidate> bestStore(BrainContext ctx) {
        if (!allowed) {
            return Optional.empty();
        }
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        double weight = ctx.profile().d(ProfileAspect.STORES_STALENESS_WEIGHT);
        long recheck = ctx.profile().i(ProfileAspect.STORES_RECHECK_TICKS);
        Candidate best = null;
        for (PoiMemory store : Store.ours(ctx)) {
            Pos at = store.anchor();
            // A chest shut to us stays unopened however often it is tried, so without this it
            // would be the cheapest way every round until the cap.
            if (ctx.knowledge().isAvoided(Store.POI, at, now)) {
                continue;
            }
            double distance = Store.distance(at, here);
            double unopened = UNOPENED_FACTOR * distance;
            Optional<Seen> seen = ctx.knowledge().insideOf(at);
            Candidate candidate;
            if (seen.isPresent() && seen.get().count(spec) > 0) {
                double stale = weight * seen.get().age(now) / 100.0;
                candidate = new Candidate(at, Math.min(distance + stale, unopened), true);
            } else if (seen.isEmpty() || seen.get().age(now) >= recheck) {
                candidate = new Candidate(at, unopened, false);
            } else {
                continue;
            }
            if (best == null || candidate.beats(best)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private record Candidate(Pos at, double cost, boolean seenHolding) {
        /** A look that capped out at the unopened price still beats not having looked. */
        boolean beats(Candidate other) {
            return cost < other.cost || (cost == other.cost && seenHolding && !other.seenHolding);
        }
    }
}
