package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.store.Depot;
import java.util.Optional;

/**
 * Where a body in trouble heads for: the shelter it remembers nearest its home, else home itself.
 * A body with no home has no refuge, and a flight runs only from what it fears.
 */
public final class Refuge {

    /** How near home a remembered shelter must be to count as home's (Luiz, 2026-10-20). */
    public static final int NEAR_HOME = 32;

    private Refuge() {
    }

    public static Optional<Pos> of(BrainContext ctx) {
        Optional<Depot.Site> site = ctx.depot();
        if (site.isEmpty()) {
            return Optional.empty();
        }
        Pos home = site.get().hint();
        Pos best = home;
        double bestDistance = NEAR_HOME;
        for (PoiMemory way : ctx.knowledge().sighted(PoiKind.SHELTER)) {
            double distance = flat(way.anchor(), home);
            if (!ShelterNoter.dug(way) && distance <= bestDistance) {
                best = way.anchor();
                bestDistance = distance;
            }
        }
        return Optional.of(best);
    }

    static double flat(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
