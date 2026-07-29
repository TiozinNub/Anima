package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The knob registry itself: keys, ranges, parsing and formatting. */
class KnobTest {

    @Test
    @DisplayName("every knob's own default sits inside its own range")
    void defaultsAreLegal() {
        // Guards the commonest way to break this file: adding a knob whose default and bounds
        // disagree, which would silently clamp on every single load.
        for (Knob knob : Knob.values()) {
            assertTrue(knob.min() <= knob.def() && knob.def() <= knob.max(),
                    knob.key() + " default " + knob.def()
                            + " is outside [" + knob.min() + ", " + knob.max() + "]");
            assertTrue(knob.accepts(knob.def()), knob.key() + " does not accept its own default");
        }
    }

    @Test
    @DisplayName("a key's path is its nesting — one object per dot, however deep it goes")
    void pathIsTheNesting() {
        for (Knob knob : Knob.values()) {
            assertEquals(List.of(knob.key().split("\\.")), knob.path());
            assertEquals(knob.section(), knob.path().get(0));
            assertEquals(knob.leaf(), knob.path().get(knob.path().size() - 1));
        }

        // A generated species aspect nests four deep, and section/leaf still name the ends of it —
        // which is what lets one render() write both shapes without knowing which it has.
        KnobSpec deep = new KnobSpec() {
            @Override public String key() { return "wolf.anima_settings.senses.radius"; }
            @Override public Kind kind() { return Kind.INT; }
            @Override public double def() { return 12; }
            @Override public double min() { return 1; }
            @Override public double max() { return 64; }
            @Override public String doc() { return "how far a wolf sees."; }
        };
        assertEquals(List.of("wolf", "anima_settings", "senses", "radius"), deep.path());
        assertEquals("wolf", deep.section());
        assertEquals("radius", deep.leaf());
    }

    @Test
    @DisplayName("keys are unique, dotted, snake_case, and split into section + leaf")
    void keysAreWellFormed() {
        Set<String> seen = new HashSet<>();
        for (Knob knob : Knob.values()) {
            assertTrue(seen.add(knob.key()), "duplicate key " + knob.key());
            assertTrue(knob.key().matches("[a-z0-9_]+\\.[a-z0-9_]+"),
                    knob.key() + " is not a dotted snake_case key");
            assertEquals(knob.key(), knob.section() + "." + knob.leaf());
            assertEquals(Optional.of(knob), Knob.byKey(knob.key()));
        }
        assertEquals(Optional.empty(), Knob.byKey("nope.nothing"));
    }

    @Test
    @DisplayName("every knob documents itself — the file and the GUI both render this")
    void docsExist() {
        for (Knob knob : Knob.values()) {
            assertFalse(knob.doc().isBlank(), knob.key() + " has no doc");
        }
    }

    @Test
    void clampBoundsAndRoundsByKind() {
        assertEquals(4096.0, Knob.READS_PER_TICK.clamp(99_999.0), "above max clamps down");
        assertEquals(1.0, Knob.READS_PER_TICK.clamp(-2.0), "below min clamps up");
        assertEquals(65.0, Knob.READS_PER_TICK.clamp(64.6), "int knobs round to whole");
        assertEquals(1.0, Knob.JOURNAL_FILE_SINK.clamp(0.7), "bool knobs round to 0 or 1");
    }

    @Test
    void parseRejectsTheWrongShape() {
        assertEquals(Optional.of(1.0), Knob.JOURNAL_FILE_SINK.parse("true"));
        assertEquals(Optional.of(0.0), Knob.JOURNAL_FILE_SINK.parse(" FALSE "));
        assertEquals(Optional.empty(), Knob.JOURNAL_FILE_SINK.parse("1"),
                "a bool knob wants true/false, not a number");

        assertEquals(Optional.of(14.0), Knob.RAY_BUDGET.parse("14"));
        assertEquals(Optional.empty(), Knob.RAY_BUDGET.parse("12.5"),
                "a whole-number knob rejects a fraction rather than silently rounding it");
        assertEquals(Optional.empty(), Knob.RAY_BUDGET.parse("twelve"));
        assertEquals(Optional.empty(), Knob.RAY_BUDGET.parse("NaN"));
        assertEquals(Optional.empty(), Knob.RAY_BUDGET.parse("Infinity"));
    }

    @Test
    @DisplayName("parse accepts out-of-range input — clamping is a separate, reportable step")
    void parseIsNotValidation() {
        assertEquals(Optional.of(999.0), Knob.RAY_BUDGET.parse("999"));
        assertFalse(Knob.RAY_BUDGET.accepts(999.0));
        assertEquals(256.0, Knob.RAY_BUDGET.clamp(999.0));
    }

    @Test
    void formatMatchesTheKind() {
        assertEquals("true", Knob.JOURNAL_FILE_SINK.format(1.0));
        assertEquals("false", Knob.JOURNAL_FILE_SINK.format(0.0));
        assertEquals("12", Knob.RAY_BUDGET.format(12.0), "int knobs render without a decimal");
    }
}
