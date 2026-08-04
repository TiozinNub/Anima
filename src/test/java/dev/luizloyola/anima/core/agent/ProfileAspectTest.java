package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.Knob;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audit, made enforceable. Everything Anima can be tuned by is either something a species
 * declares for itself ({@link ProfileAspect}) or something the server keeps for everybody
 * ({@link Knob}), and this suite fails if a new one appears that nobody classified.
 *
 * <p>The set left in {@code anima.json} is written down here with the reason each earned its
 * place, so adding to that enum fails the build until somebody says why a species may not answer
 * it.
 */
class ProfileAspectTest {

    /**
     * What stays Anima's, each with the reason it is not a way one mind differs from another.
     * Adding a constant to {@link Knob} without adding it here fails
     * {@link #animaKeepsOnlyWhatNoSpeciesMayAnswer()}.
     */
    private static final Set<Knob> SERVER_WIDE = EnumSet.of(
            // A species that could raise its own would be a species that can take a server down.
            Knob.READS_PER_TICK,
            // And its aggregate twin, which is even less a species' business: it is about how many
            // bodies there are, which no single body can see.
            Knob.READS_PER_TICK_TOTAL,
            Knob.QUEUE_CAP,
            Knob.REGION_MAX_BLOCKS,
            Knob.RAY_BUDGET,
            // Memory the WORLD spends on everybody's behalf: the shapes in it are the ground's,
            // not one kind of mind's.
            Knob.REGION_CACHE_CELLS,
            // Likewise, and for a stronger reason: which THINGS stand in a wood is a fact about
            // the wood.
            Knob.PLACE_INDEX_CELLS,
            Knob.RAYS_PER_TICK,
            // The contract of a registry two agents share — it belongs to the board, not to
            // either of them.
            Knob.CLAIM_TTL_TICKS,
            // A debugging facility and its disk use. No species has an opinion about these.
            Knob.JOURNAL_MAX_ENTRIES,
            Knob.JOURNAL_MAX_AGE_TICKS,
            Knob.JOURNAL_SWEEP_INTERVAL,
            Knob.JOURNAL_FILE_SINK,
            Knob.JOURNAL_KEEP_RUNS);

    @Test
    @DisplayName("anima.json holds only what no species may answer for itself")
    void animaKeepsOnlyWhatNoSpeciesMayAnswer() {
        Set<Knob> unjustified = EnumSet.noneOf(Knob.class);
        for (Knob knob : Knob.values()) {
            if (!SERVER_WIDE.contains(knob)) {
                unjustified.add(knob);
            }
        }
        assertTrue(unjustified.isEmpty(),
                "new knobs with no answer to 'may a species set this?' — either make it a "
                        + "ProfileAspect, or list it above with the reason it is the operator's: "
                        + unjustified);
    }

    @Test
    @DisplayName("the two key spaces never collide")
    void theTwoKeySpacesAreDisjoint() {
        for (Knob knob : Knob.values()) {
            assertTrue(ProfileAspect.byKey(knob.key()).isEmpty(),
                    knob.key() + " is declared both per-species and server-wide");
        }
        for (ProfileAspect aspect : ProfileAspect.values()) {
            assertTrue(Knob.byKey(aspect.key()).isEmpty(),
                    aspect.key() + " is declared both per-species and server-wide");
        }
    }

    @Test
    @DisplayName("aspects are well formed: unique dotted keys, a real range, a sentence each")
    void aspectsAreWellFormed() {
        Set<String> seen = new HashSet<>();
        for (ProfileAspect aspect : ProfileAspect.values()) {
            assertTrue(seen.add(aspect.key()), "duplicate aspect key " + aspect.key());
            assertTrue(aspect.key().matches("[a-z0-9_]+\\.[a-z0-9_]+"),
                    aspect.key() + " is not a dotted snake_case key");
            assertTrue(aspect.min() < aspect.max(), aspect.key() + " has an empty range");
            assertTrue(aspect.doc().length() > 20 && aspect.doc().endsWith("."),
                    aspect.key() + " needs a sentence for the operator");
            assertEquals(java.util.Optional.of(aspect), ProfileAspect.byKey(aspect.key()));
        }
        assertEquals(java.util.Optional.empty(), ProfileAspect.byKey("nope.nothing"));
    }

    @Test
    @DisplayName("Anima ships no values — an aspect has bounds and no default")
    void aspectsCarryNoDefault() {
        // No fallback anywhere in the tier: a species that fails to declare an aspect must fail
        // loudly rather than inherit a library's idea of how far a body sees.
        for (java.lang.reflect.Method method : ProfileAspect.class.getDeclaredMethods()) {
            assertTrue(!method.getName().equals("def") && !method.getName().equals("defaultValue"),
                    "ProfileAspect grew a default: " + method.getName());
        }
    }
}
