package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.gate.Act;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Yields;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Get what is wanted by killing an animal that drops it (directions spec, rung 4). A producer: the
 * consumer registers it under the food it wants, and it competes with every other way on price.
 *
 * <p>Three plans, the first that applies:
 * <ul>
 *   <li><b>Prey in view</b> ({@link Prey}): {@link Fight} it, then pick up what it dropped. Priced
 *       at the distance plus {@link #WORK}.</li>
 *   <li><b>A remembered herd or loner</b> of a prey species: {@link Scout} its ground. Seeing one
 *       ends the scout, and the obtain's next round finds it in view. Priced at the walk plus
 *       {@link #WORK}.</li>
 *   <li><b>Nothing known</b>: {@link SeekPrey}, a flat {@link #SEARCH_COST} — over a peckish
 *       body's 15, under a hungry one's 60. A search that found nothing rests the ground it
 *       started on, so hunger asking again does not send the body straight back out.</li>
 * </ul>
 */
public final class Hunt implements Method {

    /** Walk-blocks the kill and the pickup are worth on top of the walk. */
    public static final double WORK = 12.0;

    public static final double SEARCH_COST = 40.0;

    private final ItemSpec wanted;
    private final @Nullable Act act;

    /**
     * @param wanted what the goal asks for; only animals dropping it are prey
     * @param act the act the gate must allow, or null for none
     */
    public Hunt(ItemSpec wanted, @Nullable Act act) {
        this.wanted = wanted;
        this.act = act;
    }

    @Override
    public boolean applicable(BrainContext ctx) {
        return (act == null || ctx.gate().mayDo(act)) && plan(ctx) != null;
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        Plan plan = plan(ctx);
        return plan == null ? Double.POSITIVE_INFINITY : plan.cost();
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Plan plan = plan(ctx);
        if (plan == null) {
            throw new IllegalStateException("Hunt.decompose with nothing to hunt — applicable() gates this");
        }
        if (plan.target() != null) {
            Being prey = plan.target();
            ctx.journal().record(Category.BRAIN, "hunt", "hunting a " + prey.species() + " at "
                    + prey.pos().x() + ", " + prey.pos().y() + ", " + prey.pos().z());
            return List.of(new Fight(prey.id(), prey.pos()),
                    new Try(new GatherNearbyDrops(ItemSpec.anyOf(Yields.of(prey.species())))));
        }
        if (plan.herd() != null) {
            PoiMemory herd = plan.herd();
            return List.of(new Scout(herd.detail(), herd.anchor(), Scout.radiusOf(herd.bounds())));
        }
        return List.of(new SeekPrey(wanted));
    }

    @Override
    public String describe() {
        return "hunt for " + wanted.name();
    }

    /** One of the three plans; exactly one of target and herd is set, or neither for a search. */
    private record Plan(@Nullable Being target, @Nullable PoiMemory herd, double cost) {
    }

    private @Nullable Plan plan(BrainContext ctx) {
        Being prey = Prey.nearest(ctx, species -> Prey.yields(ctx, species, wanted)).orElse(null);
        if (prey != null) {
            return new Plan(prey, null, prey.distance() + WORK);
        }
        Pos here = ctx.percepts().position();
        PoiMemory best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PoiMemory herd : ctx.knowledge().all(PoiKind.HERD)) {
            if (!Prey.yields(ctx, herd.detail(), wanted)) {
                continue;
            }
            double distance = distance(here, herd.anchor());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = herd;
            }
        }
        if (best != null) {
            return new Plan(null, best, bestDistance + WORK);
        }
        if (ctx.knowledge().isAvoided(PoiKind.HERD, SeekPrey.restKey(here), ctx.percepts().time())
                || !Prey.anyYields(ctx, wanted)) {
            return null;
        }
        return new Plan(null, null, SEARCH_COST);
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
