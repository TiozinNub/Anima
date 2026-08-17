package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KnobSpec.Kind#STRING}: text lives beside the double array at the same slot, and every
 * guarantee the numeric kinds carry holds for it too — always valid by construction, clean round
 * trip, defaults recognised.
 *
 * <p>The last two tests are the boundary: {@code Kind} is shared with {@code ProfileAspect} and
 * {@code NeedKind}, and neither has any meaning for text.
 */
class StringKnobTest {

    /** A set with both kinds, so the parallel arrays have to stay aligned to pass. */
    private enum Mixed implements KnobSpec {
        COUNT("test.count", Kind.INT, 5.0, 0.0, 10.0, ""),
        URL("test.url", Kind.STRING, 0.0, 1.0, 40.0, "https://example.invalid/app.js"),
        RATIO("test.ratio", Kind.DOUBLE, 0.5, 0.0, 1.0, "");

        private final String key;
        private final Kind kind;
        private final double def;
        private final double min;
        private final double max;
        private final String defText;

        Mixed(String key, Kind kind, double def, double min, double max, String defText) {
            this.key = key;
            this.kind = kind;
            this.def = def;
            this.min = min;
            this.max = max;
            this.defText = defText;
        }

        @Override public String key() { return key; }
        @Override public Kind kind() { return kind; }
        @Override public double def() { return def; }
        @Override public double min() { return min; }
        @Override public double max() { return max; }
        @Override public String defText() { return defText; }
        @Override public String doc() { return "test"; }
    }

    private static KnobSet set() {
        return KnobSet.of("test", "Test", Mixed.values());
    }

    @Test
    @DisplayName("a text knob starts at its own default, and the numeric knobs beside it are untouched")
    void defaultsCoverBothKinds() {
        ConfigValues config = set().defaults();
        assertEquals("https://example.invalid/app.js", config.s(Mixed.URL));
        assertEquals(5, config.i(Mixed.COUNT));
        assertEquals(0.5, config.d(Mixed.RATIO));
        for (KnobSpec knob : set().knobs()) {
            assertTrue(config.isDefault(knob), knob.key());
        }
        assertTrue(config.describeOverrides().isEmpty());
    }

    @Test
    @DisplayName("setting text leaves the numeric slots alone, and vice versa")
    void thePairOfArraysStayAligned() {
        ConfigValues config = set().defaults()
                .with(Mixed.URL, "http://localhost:5173/app.js")
                .with(Mixed.COUNT, 9.0);
        assertEquals("http://localhost:5173/app.js", config.s(Mixed.URL));
        assertEquals(9, config.i(Mixed.COUNT));
        assertEquals(0.5, config.d(Mixed.RATIO), "an untouched knob moved");
        assertFalse(config.isDefault(Mixed.URL));
        assertFalse(config.isDefault(Mixed.COUNT));
        assertTrue(config.isDefault(Mixed.RATIO));
    }

    @Test
    @DisplayName("out-of-range text falls back to the default rather than being truncated")
    void sanitiseFallsBackWholesale() {
        String tooLong = "x".repeat(41);
        assertEquals(Mixed.URL.defText(), Mixed.URL.sanitise(tooLong),
                "half a URL is a value that looks set and does not work");
        assertEquals(Mixed.URL.defText(), Mixed.URL.sanitise(""),
                "below min length is out of range too — an empty URL is not a URL");
        assertEquals("http://a", Mixed.URL.sanitise("  http://a  "), "not trimmed first");
        assertTrue(Mixed.URL.acceptsText("http://a"));
        assertFalse(Mixed.URL.acceptsText(tooLong));
    }

    @Test
    @DisplayName("a set text knob survives a file round trip beside the numeric ones")
    void roundTripsThroughBothMaps() {
        KnobSet set = set();
        ConfigValues source = set.defaults()
                .with(Mixed.URL, "http://localhost:5173/app.js")
                .with(Mixed.COUNT, 7.0);
        ConfigValues.Loaded round =
                ConfigValues.from(set, source.toMap(), source.toTextMap());
        assertTrue(round.clean(), () -> String.valueOf(round.problems()));
        assertEquals(source, round.config());
    }

    @Test
    @DisplayName("a text knob out of range in the file is reported, not silently accepted")
    void loadingReportsCorrectedText() {
        KnobSet set = set();
        ConfigValues.Loaded loaded = ConfigValues.from(
                set, Map.of(), Map.of(Mixed.URL, "y".repeat(99)));
        assertFalse(loaded.clean());
        assertEquals(Mixed.URL.defText(), loaded.config().s(Mixed.URL));
        assertTrue(loaded.problems().get(0).contains("test.url"), loaded.problems().get(0));
    }

    @Test
    @DisplayName("the wrong with() overload is refused rather than writing where nothing reads")
    void mismatchedOverloadsThrow() {
        ConfigValues config = set().defaults();
        assertThrows(IllegalArgumentException.class, () -> config.with(Mixed.URL, 3.0));
        assertThrows(IllegalArgumentException.class, () -> config.with(Mixed.COUNT, "three"));
        assertThrows(UnsupportedOperationException.class, () -> Mixed.URL.format(1.0));
    }

    @Test
    @DisplayName("describeOverrides and text() render either kind without the caller branching")
    void renderingIsKindAgnostic() {
        ConfigValues config = set().defaults().with(Mixed.URL, "http://localhost:5173/app.js");
        assertEquals("\"http://localhost:5173/app.js\"", config.text(Mixed.URL));
        assertEquals("5", config.text(Mixed.COUNT));
        assertEquals(List.of("test.url = \"http://localhost:5173/app.js\" "
                + "(default \"https://example.invalid/app.js\")"), config.describeOverrides());
    }

    @Test
    @DisplayName("a species aspect cannot hold text — it is a numeric dial the brain arithmetics on")
    void profileAspectRefusesString() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                ProfileAspect.register("test.textual", KnobSpec.Kind.STRING, 0, 1, "Not a dial."));
        assertTrue(thrown.getMessage().contains("numeric dial"), thrown.getMessage());
    }

    @Test
    @DisplayName("a need gauge cannot hold text — it reads a point on an axis")
    void needKindRefusesString() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                NeedKind.declare("test_textual", KnobSpec.Kind.STRING, 0.0, 1.0, "nothing"));
        assertTrue(thrown.getMessage().contains("axis"), thrown.getMessage());
    }
}
