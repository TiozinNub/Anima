package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.Seen;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Optional;

/**
 * Take it out of a store somebody already filled — the cheapest way to have a thing when a party
 * has been putting things away.
 *
 * <p>Applicable only against a store this body has <em>looked inside</em>: a remembered place says
 * where a chest is, never what is in it. Priced at distance plus staleness, so a fresh belief nearby
 * beats felling a tree and an hour-old one ninety blocks off does not.
 */
public final class TakeFromStore implements Method {
    private final ItemSpec spec;
    private final int count;

    public TakeFromStore(ItemSpec spec, int count) {
        this.spec = spec;
        this.count = count;
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
        Pos anchor = bestStore(ctx).orElseThrow().memory().anchor();
        Pos beside = standableBeside(anchor, ctx);
        return List.of(
                new GoTo(beside.x(), beside.y(), beside.z()),
                new TakeItems(anchor, spec, count));
    }

    @Override
    public String describe() {
        return "take " + spec.name() + " from a store";
    }

    /**
     * The cheapest known store this body has actually looked inside and believes holds the spec —
     * a remembered anchor alone says nothing about contents, which is why {@code insideOf} gates
     * every candidate here rather than {@code Store.POI} membership alone.
     */
    private Optional<Candidate> bestStore(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        double weight = ctx.profile().d(ProfileAspect.STORES_STALENESS_WEIGHT);
        Candidate best = null;
        for (PoiMemory memory : ctx.knowledge().all(Store.POI)) {
            Optional<Seen> seen = ctx.knowledge().insideOf(memory.anchor());
            if (seen.isEmpty() || seen.get().count(spec) <= 0) {
                continue;
            }
            double cost = Store.distance(memory.anchor(), here) + weight * seen.get().age(now) / 100.0;
            if (best == null || cost < best.cost()) {
                best = new Candidate(memory, cost);
            }
        }
        return Optional.ofNullable(best);
    }

    private record Candidate(PoiMemory memory, double cost) {
    }

    /**
     * A cell to stand in beside the store: an empty side neighbour, else the anchor's column —
     * same shape as {@link EnsureTable.WalkToKnown}'s own {@code standableBeside}, reused rather
     * than re-derived.
     */
    private static Pos standableBeside(Pos anchor, BrainContext ctx) {
        BlockProbe probe = ctx.percepts().blocks();
        Pos best = null;
        double bestDistance = Double.MAX_VALUE;
        Pos here = ctx.percepts().position();
        for (int[] side : SIDES) {
            Pos cell = new Pos(anchor.x() + side[0], anchor.y(), anchor.z() + side[1]);
            if (probe.at(cell.x(), cell.y(), cell.z()) != BlockKind.AIR) {
                continue;
            }
            double distance = Store.distance(cell, here);
            if (distance < bestDistance) {
                best = cell;
                bestDistance = distance;
            }
        }
        return best != null ? best : anchor;
    }

    /** The eight horizontal neighbours, sides first — a store in a corner is awkward to reach. */
    private static final int[][] SIDES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
}
