package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The immutable value holder: defaults, clamping-with-report, copy-on-write, and the live holder. */
class ConfigValuesTest {

    @AfterEach
    void restoreGlobalConfig() {
        // Config is process-wide; leaving a custom one installed would leak into every other test.
        Config.reset();
    }

    @Test
    void defaultsHoldEveryKnobsDeclaredDefault() {
        for (Knob knob : Knob.values()) {
            assertEquals(knob.def(), Config.SET.defaults().get(knob), knob.key());
            assertTrue(Config.SET.defaults().isDefault(knob), knob.key());
        }
        assertTrue(Config.SET.defaults().describeOverrides().isEmpty());
    }

    @Test
    @DisplayName("an empty input means defaults, with nothing to report")
    void emptyInputIsClean() {
        ConfigValues.Loaded loaded = ConfigValues.from(Config.SET, Map.of());
        assertTrue(loaded.clean());
        assertEquals(Config.SET.defaults(), loaded.config());
    }

    @Test
    @DisplayName("absent knobs keep their default; only what was supplied changes")
    void partialInputLeavesTheRestAlone() {
        ConfigValues.Loaded loaded = ConfigValues.from(Config.SET, Map.of(Knob.RAY_BUDGET, 20.0));
        assertTrue(loaded.clean());
        assertEquals(20, loaded.config().i(Knob.RAY_BUDGET));
        assertEquals(Knob.READS_PER_TICK.def(), loaded.config().get(Knob.READS_PER_TICK));
        assertFalse(loaded.config().isDefault(Knob.RAY_BUDGET));
        assertTrue(loaded.config().isDefault(Knob.READS_PER_TICK));
    }

    @Test
    @DisplayName("an out-of-range value is clamped AND reported — never silently swallowed")
    void outOfRangeIsClampedAndReported() {
        Map<KnobSpec, Double> raw = new java.util.LinkedHashMap<>();
        raw.put(Knob.RAY_BUDGET, 999.0);
        raw.put(Knob.QUEUE_CAP, -1.0);
        ConfigValues.Loaded loaded = ConfigValues.from(Config.SET, raw);

        assertFalse(loaded.clean());
        assertEquals(2, loaded.problems().size(), loaded.problems().toString());
        assertEquals(256, loaded.config().i(Knob.RAY_BUDGET));
        assertEquals(16, loaded.config().i(Knob.QUEUE_CAP));
        assertTrue(loaded.problems().stream().anyMatch(p -> p.startsWith(Knob.RAY_BUDGET.key())),
                loaded.problems().toString());
    }

    @Test
    @DisplayName("there is no way to build a config holding an illegal value")
    void withAlwaysClamps() {
        assertEquals(256, Config.SET.defaults().with(Knob.RAY_BUDGET, 10_000.0).i(Knob.RAY_BUDGET),
                "above the max clamps down to it");
        assertEquals(1, Config.SET.defaults().with(Knob.RAY_BUDGET, -5.0).i(Knob.RAY_BUDGET),
                "below the min clamps up to it");
        assertEquals(13, Config.SET.defaults().with(Knob.RAY_BUDGET, 12.6).i(Knob.RAY_BUDGET),
                "a fractional value for a whole-number knob is rounded, not truncated");
        for (Knob knob : Knob.values()) {
            if (knob.kind().textual()) {
                // The same "no illegal value can be built" guarantee, through the text overload:
                // an over-long string falls back to the default rather than being stored.
                ConfigValues absurd = Config.SET.defaults().with(knob, "x".repeat(4096));
                assertTrue(knob.acceptsText(absurd.s(knob)),
                        knob.key() + " survived an absurd value");
                continue;
            }
            ConfigValues absurd = Config.SET.defaults().with(knob, Double.MAX_VALUE);
            assertTrue(knob.accepts(absurd.get(knob)), knob.key() + " survived an absurd value");
        }
    }

    @Test
    void withIsCopyOnWriteAndLeavesTheOriginalAlone() {
        ConfigValues original = Config.SET.defaults();
        ConfigValues changed = original.with(Knob.JOURNAL_SWEEP_INTERVAL, 24.0);

        assertEquals(24, changed.i(Knob.JOURNAL_SWEEP_INTERVAL));
        assertEquals((int) Knob.JOURNAL_SWEEP_INTERVAL.def(), original.i(Knob.JOURNAL_SWEEP_INTERVAL),
                "the original must be untouched — readers may be holding it mid-tick");
        assertNotEquals(original, changed);
        assertEquals(original, Config.SET.defaults());
    }

    @Test
    void toMapRoundTripsThroughFrom() {
        ConfigValues source = Config.SET.defaults()
                .with(Knob.RAY_BUDGET, 20.0)
                .with(Knob.JOURNAL_FILE_SINK, 1.0)
                .with(Knob.REGION_MAX_BLOCKS, 0.25);
        ConfigValues.Loaded round = ConfigValues.from(Config.SET, source.toMap());

        assertTrue(round.clean());
        assertEquals(source, round.config());
    }

    @Test
    void describeOverridesNamesOnlyWhatDiffers() {
        ConfigValues config = Config.SET.defaults().with(Knob.CLAIM_TTL_TICKS, 1200.0);
        List<String> overrides = config.describeOverrides();

        assertEquals(1, overrides.size(), overrides.toString());
        assertTrue(overrides.get(0).startsWith("claims.ttl_ticks = 1200"), overrides.get(0));
        assertTrue(overrides.get(0).contains("default 600"), overrides.get(0));
    }

    @Test
    @DisplayName("the live holder starts at defaults, swaps whole, and resets")
    void holderSwapsAtomically() {
        assertSame(Config.SET.defaults(), Config.get());

        ConfigValues custom = Config.SET.defaults().with(Knob.RAY_BUDGET, 5.0);
        Config.install(custom);
        assertSame(custom, Config.get());
        assertEquals(5, Config.get().i(Knob.RAY_BUDGET));

        Config.install(null);
        assertSame(Config.SET.defaults(), Config.get(), "a null install falls back, never NPEs");

        Config.install(custom);
        Config.reset();
        assertSame(Config.SET.defaults(), Config.get());
    }
}
