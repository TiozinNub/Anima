package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The set owns the storage index. These are the guarantees that let a set be assembled from more
 * than one enum — the shape a generated per-species knob family arrives in.
 */
class KnobSetTest {

    /** One mod's own tunables. Note it starts counting at zero, like every enum. */
    private enum Mine implements KnobSpec {
        ALPHA("mine.alpha", 1.0),
        BETA("mine.beta", 2.0);

        private final String key;
        private final double def;

        Mine(String key, double def) {
            this.key = key;
            this.def = def;
        }

        @Override public String key() { return key; }
        @Override public Kind kind() { return Kind.DOUBLE; }
        @Override public double def() { return def; }
        @Override public double min() { return 0.0; }
        @Override public double max() { return 100.0; }
        @Override public String doc() { return "mine"; }
    }

    /** A second, independently declared enum — and it starts counting at zero too. */
    private enum Generated implements KnobSpec {
        GAMMA("gen.gamma", 3.0),
        DELTA("gen.delta", 4.0);

        private final String key;
        private final double def;

        Generated(String key, double def) {
            this.key = key;
            this.def = def;
        }

        @Override public String key() { return key; }
        @Override public Kind kind() { return Kind.DOUBLE; }
        @Override public double def() { return def; }
        @Override public double min() { return 0.0; }
        @Override public double max() { return 100.0; }
        @Override public String doc() { return "generated"; }
    }

    private static KnobSet mixed() {
        List<KnobSpec> knobs = new ArrayList<>(List.of(Mine.values()));
        knobs.addAll(List.of(Generated.values()));
        return KnobSet.of("mixed", "Mixed", knobs);
    }

    @Test
    @DisplayName("two enums in one set get four distinct slots, not two shared ones")
    void independentEnumsDoNotCollide() {
        KnobSet set = mixed();
        Set<Integer> slots = new HashSet<>();
        for (KnobSpec knob : set.knobs()) {
            assertTrue(slots.add(set.indexOf(knob)), knob.key() + " shares a slot");
        }
        assertEquals(4, slots.size());
        assertEquals(Set.of(0, 1, 2, 3), slots, "slots are declaration order, densely packed");
    }

    @Test
    @DisplayName("each knob keeps its own value — the collision this replaced overwrote them")
    void valuesStaySeparate() {
        KnobSet set = mixed();
        // Mine.ALPHA and Generated.GAMMA are both ordinal 0. Under the old indexing this write
        // landed on both, and reading either returned 9.0.
        ConfigValues config = set.defaults().with(Mine.ALPHA, 9.0);

        assertEquals(9.0, config.d(Mine.ALPHA));
        assertEquals(Generated.GAMMA.def(), config.d(Generated.GAMMA), "a neighbour was overwritten");
        assertEquals(Mine.BETA.def(), config.d(Mine.BETA));
        assertEquals(Generated.DELTA.def(), config.d(Generated.DELTA));

        assertEquals(List.of("mine.alpha = 9.0 (default 1.0)"), config.describeOverrides());
    }

    @Test
    @DisplayName("a whole round trip through from() keeps the two enums apart")
    void loadRoundTripsAcrossBothEnums() {
        KnobSet set = mixed();
        ConfigValues source = set.defaults().with(Mine.BETA, 7.0).with(Generated.DELTA, 8.0);
        ConfigValues.Loaded round = ConfigValues.from(set, source.toMap());

        assertTrue(round.clean(), round.problems().toString());
        assertEquals(source, round.config());
        assertEquals(7.0, round.config().d(Mine.BETA));
        assertEquals(8.0, round.config().d(Generated.DELTA));
    }

    @Test
    @DisplayName("a knob from another set is refused, not silently read off a neighbour")
    void foreignKnobIsRefused() {
        KnobSet set = mixed();
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> set.defaults().d(Knob.SENSE_RADIUS));
        assertTrue(thrown.getMessage().contains(Knob.SENSE_RADIUS.key()), thrown.getMessage());
    }

    @Test
    @DisplayName("two knobs claiming one key is a declaration bug, and fails at construction")
    void duplicateKeysAreRejected() {
        List<KnobSpec> clashing = List.of(Mine.ALPHA, Generated.GAMMA, Mine.ALPHA);
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> KnobSet.of("dup", "Dup", clashing));
        assertTrue(thrown.getMessage().contains("mine.alpha"), thrown.getMessage());
    }

    @Test
    @DisplayName("byKey finds every knob of a mixed set, and nothing else")
    void byKeyReachesBothEnums() {
        KnobSet set = mixed();
        for (KnobSpec knob : set.knobs()) {
            assertEquals(java.util.Optional.of(knob), set.byKey(knob.key()));
        }
        assertEquals(java.util.Optional.empty(), set.byKey("nope.nothing"));
    }

    @Test
    @DisplayName("Anima's own set is unchanged by all this — same knobs, same slots, same defaults")
    void animasOwnSetStillIndexesInDeclarationOrder() {
        for (Knob knob : Knob.values()) {
            assertEquals(knob.ordinal(), Config.SET.indexOf(knob), knob.key());
        }
        assertEquals(Knob.values().length, Config.SET.size());
    }

    @Test
    @DisplayName("a set's values do not equal another set's, even knob for knob")
    void valuesCarryTheirSetIdentity() {
        assertNotEquals(mixed().defaults(), KnobSet.of("other", "Other", List.of(Mine.values()))
                .defaults());
    }
}
