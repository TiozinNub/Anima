package dev.luizloyola.anima.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KnobSpec.Kind#LIST}: an array in the file, one comma-joined string in memory. The
 * encoding is the whole risk, so the tests that matter are the ones that cross it — what
 * {@link ConfigFile#render} writes, and that night-config reads it back as the array it wrote.
 */
class ListKnobTest {

    private enum Mixed implements KnobSpec {
        COUNT("test.count", Kind.INT, 5.0, 0.0, 10.0, ""),
        ALLOWED("test.allowed", Kind.LIST, 0.0, 0.0, 40.0, "");

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
    @DisplayName("splitting and joining are inverses, and normalise on the way")
    void theEncodingRoundTrips() {
        assertEquals(List.of("a-b", "c-d"), KnobSpec.splitList("a-b,c-d"));
        assertEquals(List.of("a-b", "c-d"), KnobSpec.splitList(" a-b ,  c-d "));
        assertEquals(List.of(), KnobSpec.splitList(""));
        assertEquals(List.of("a-b"), KnobSpec.splitList("a-b,,a-b"), "blanks and repeats go");
        assertEquals("a-b,c-d", KnobSpec.joinList(List.of("a-b", " c-d ", "a-b")));
        assertEquals("", KnobSpec.joinList(List.of()));
    }

    @Test
    @DisplayName("a stray space normalises rather than dropping the operator's whole list")
    void sanitiseNormalises() {
        assertEquals("a-b,c-d", Mixed.ALLOWED.sanitise("a-b, c-d"));
        assertTrue(Mixed.ALLOWED.acceptsText("a-b,c-d"));
        assertFalse(Mixed.ALLOWED.acceptsText("a-b, c-d"), "reported, then normalised");
        // Only length falls back, and wholesale: half a list is worse than the default it replaced.
        assertEquals("", Mixed.ALLOWED.sanitise("x".repeat(41)));
    }

    @Test
    @DisplayName("it displays as the array the file holds, not as its stored spelling")
    void displayIsTheFilesShape() {
        assertEquals("[\"a-b\", \"c-d\"]", Mixed.ALLOWED.formatText("a-b,c-d"));
        assertEquals("[]", Mixed.ALLOWED.formatText(""));
        ConfigValues config = set().defaults().with(Mixed.ALLOWED, "a-b");
        assertEquals("[\"a-b\"]", config.text(Mixed.ALLOWED));
        assertEquals(List.of("test.allowed = [\"a-b\"] (default [])"), config.describeOverrides());
    }

    @Test
    @DisplayName("a list survives the file round trip as a TOML array beside the numeric knobs")
    void roundTripsThroughToml() {
        KnobSet set = set();
        ConfigStore store = new ConfigStore(set);
        ConfigFile file = new ConfigFile(store);
        ConfigValues source = set.defaults().with(Mixed.ALLOWED, "a-b,c-d").with(Mixed.COUNT, 7.0);

        String rendered = file.render(source);
        assertTrue(rendered.contains("allowed = [\"a-b\", \"c-d\"]"), rendered);

        // The half reload() does: what night-config gives back has to be the list that went in,
        // element types included — a stringified array would read back as one long entry.
        CommentedConfig parsed = TomlDocument.parse(rendered);
        assertEquals(List.of("a-b", "c-d"), parsed.get(List.of("test", "allowed")));
        assertEquals(7L, ((Number) parsed.get(List.of("test", "count"))).longValue());
    }

    @Test
    @DisplayName("an empty list is written as an empty array, not as a missing key")
    void anEmptyListStillAppears() {
        String rendered = new ConfigFile(new ConfigStore(set())).render(set().defaults());
        assertTrue(rendered.contains("allowed = []"), rendered);
        assertEquals(List.of(), TomlDocument.parse(rendered).get(List.of("test", "allowed")));
    }
}
