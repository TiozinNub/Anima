package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.config.KnobSpec;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audit, made enforceable. Every knob Anima has is either something a species declares for
 * itself or something the server keeps for everybody, and this suite fails if one is neither —
 * so knob #38 cannot be added without somebody answering the question.
 */
class ProfileAspectTest {

    /**
     * The knobs not per-species, each with the reason it failed the test. Two
     * reasons only: a species could spend the server's budget with it, or it is not a way one
     * mind differs from another.
     */
    private static final Set<Knob> SERVER_WIDE = EnumSet.of(
            // A species that could raise its own would be a species that can take a server down.
            Knob.READS_PER_TICK,
            Knob.QUEUE_CAP,
            Knob.REGION_MAX_BLOCKS,
            Knob.PEERS_RAY_BUDGET,
            // The contract of a registry two agents share — it belongs to the board, not to
            // either of them.
            Knob.CLAIM_TTL_TICKS,
            // A debugging facility and its disk use. No species has an opinion about these.
            Knob.JOURNAL_MAX_ENTRIES,
            Knob.JOURNAL_MAX_AGE_TICKS,
            Knob.JOURNAL_SWEEP_INTERVAL,
            Knob.JOURNAL_FILE_SINK);

    @Test
    @DisplayName("every knob is classified — a new one must be an aspect or a server-wide cap")
    void theAuditCoversEveryKnob() {
        Set<String> aspectKeys = new HashSet<>();
        for (ProfileAspect aspect : ProfileAspect.values()) {
            aspectKeys.add(aspect.key());
        }

        List<String> unclassified = new ArrayList<>();
        for (Knob knob : Knob.values()) {
            if (!aspectKeys.contains(knob.key()) && !SERVER_WIDE.contains(knob)) {
                unclassified.add(knob.key());
            }
        }

        assertTrue(unclassified.isEmpty(),
                "new knobs with no answer to 'is this per-species?' — add each to ProfileAspect "
                        + "or to SERVER_WIDE with its reason: " + unclassified);
        assertEquals(Knob.values().length, aspectKeys.size() + SERVER_WIDE.size(),
                "the two sets must partition the knobs, not overlap");
    }

    @Test
    @DisplayName("nothing is in both sets")
    void theTwoSetsAreDisjoint() {
        for (Knob knob : SERVER_WIDE) {
            assertTrue(ProfileAspect.byKey(knob.key()).isEmpty(),
                    knob.key() + " is declared both per-species and server-wide");
        }
    }

    @Test
    @DisplayName("an aspect and the knob it will replace agree on type and bounds")
    void aspectsDoNotDriftFromTheKnobsTheyMirror() {
        // The two live side by side until the aspect knobs leave anima.json. Prose is allowed to
        // differ (the aspect's doc speaks to whoever is describing a species), but a value legal
        // in one and illegal in the other would make the migration lossy in a way nobody sees.
        for (ProfileAspect aspect : ProfileAspect.values()) {
            KnobSpec knob = Knob.byKey(aspect.key()).orElseThrow(
                    () -> new AssertionError(aspect.key() + " matches no knob"));
            assertEquals(knob.kind(), aspect.kind(), aspect.key() + " kind");
            assertEquals(knob.min(), aspect.min(), aspect.key() + " min");
            assertEquals(knob.max(), aspect.max(), aspect.key() + " max");
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
