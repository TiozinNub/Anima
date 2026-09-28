package dev.luizloyola.anima.core.brain.history;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * A body as a deed names it: the way its teller knows it, fixed when the deed is recorded. The
 * slot form of {@link Being#knownAs()} — a name once introduced, "a stranger" seen but never
 * named, "someone" only heard, a creature its species.
 *
 * <p>A consumer that tells some bodies apart without a name — a raider, a neighbour's dog —
 * registers a {@link Teller}, asked before those defaults.
 */
public final class Whom {

    /** Every line opens a sentence with its slot, so these are capitalised. */
    public static final Slot STRANGER = Slot.lang("anima.whom.stranger");
    public static final Slot SOMEONE = Slot.lang("anima.whom.someone");

    /** One consumer's word for a body, or empty to leave it to the next. */
    @FunctionalInterface
    public interface Teller {
        Optional<Slot> tell(BrainContext ctx, Being being);
    }

    private static final List<Teller> TELLERS = new CopyOnWriteArrayList<>();

    private Whom() {
    }

    /** Tellers are asked in registration order; the first to answer wins. */
    public static void register(Teller teller) {
        TELLERS.add(teller);
    }

    /** {@code being} as {@code ctx}'s body would name it, {@link Doings#SOMETHING} for nobody. */
    public static Slot of(BrainContext ctx, @Nullable Being being) {
        if (being == null || being.identified() == Being.Identified.NONE) {
            return Doings.SOMETHING; // nothing made out, so nothing for a teller to go on
        }
        for (Teller teller : TELLERS) {
            Optional<Slot> told = teller.tell(ctx, being);
            if (told.isPresent()) {
                return told.get();
            }
        }
        boolean seen = being.identified() == Being.Identified.INDIVIDUAL;
        if (seen && !being.name().isEmpty()) {
            return Slot.name(being.name());
        }
        if (being.kind().minded()) {
            return seen ? STRANGER : SOMEONE;
        }
        return being.species().isEmpty() ? Doings.SOMETHING : Slot.entity(being.species());
    }
}
