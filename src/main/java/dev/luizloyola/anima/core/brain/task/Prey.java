package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Quarry;
import dev.luizloyola.anima.core.brain.sense.Yields;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a hunt may kill: an animal whose loot drops what is wanted, of a species this body does not
 * fear, and — read off the body — neither young nor anybody's. Fear is the consumer's own danger
 * table, so which animals are prey is the consumer's without a table of its own: a polar bear
 * drops fish, and a Person still leaves it alone.
 */
final class Prey {

    private Prey() {
    }

    /** Whether a species is worth hunting for {@code wanted}, by what it drops and whether it is feared. */
    static boolean yields(BrainContext ctx, String species, ItemSpec wanted) {
        if (species.isEmpty() || ctx.danger().weight(species) > 0.0) {
            return false;
        }
        for (String id : Yields.of(species)) {
            if (wanted.matches(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any species this body would hunt drops {@code wanted} — whether a search could end. */
    static boolean anyYields(BrainContext ctx, ItemSpec wanted) {
        for (String species : Yields.species()) {
            if (yields(ctx, species, wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The nearest fair animal in view that {@code species} accepts, a herd read out head by head. */
    static Optional<Being> nearest(BrainContext ctx, Predicate<String> species) {
        Being best = null;
        for (Being being : ctx.percepts().beings()) {
            if (being.kind().minded() || being.playerControlled() || being.aggressive()
                    || !species.test(being.species())) {
                continue;
            }
            if (being.members().isEmpty()) {
                best = nearer(ctx, being, best);
                continue;
            }
            for (var member : being.members()) {
                Optional<Being> one = ctx.percepts().being(member);
                if (one.isPresent()) {
                    best = nearer(ctx, one.get(), best);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Being nearer(BrainContext ctx, Being candidate, Being best) {
        boolean fair = ctx.percepts().quarry(candidate.id()).map(Quarry::fair).orElse(false);
        return fair && (best == null || candidate.distance() < best.distance()) ? candidate : best;
    }
}
