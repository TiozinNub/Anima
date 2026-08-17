package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KnobSpec.Kind#KEY}: a secret the installation makes for itself, empty until it does.
 *
 * <p>The interesting cases are all about <em>when</em> it is generated — never at
 * {@code defaults()}, which has to stay a stable shared constant, and exactly once at load.
 */
class GeneratedKeyTest {

    private enum Mixed implements KnobSpec {
        PORT("test.port", Kind.INT, 25_599.0, 1024.0, 65_535.0, ""),
        SECRET("test.secret", Kind.KEY, 0.0, Keys.LENGTH, 64.0, "");

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
    @DisplayName("a generated key is 16 alphanumerics and nothing else")
    void generateMatchesTheContract() {
        for (int i = 0; i < 200; i++) {
            String key = Keys.generate();
            assertEquals(Keys.LENGTH, key.length(), key);
            assertTrue(key.matches("[a-zA-Z0-9]+"),
                    "a key ends up in a URL and a TOML file — nothing in it may need escaping: " + key);
            assertTrue(Keys.wellFormed(key), key);
        }
    }

    @Test
    @DisplayName("keys do not repeat — a seeded PRNG would be no guard at all")
    void generateDoesNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue(seen.add(Keys.generate()), "generate() returned a duplicate");
        }
    }

    @Test
    @DisplayName("defaults leave it EMPTY — a shared constant must not carry a fresh secret")
    void defaultsDoNotGenerate() {
        // KnobSet.defaults() is built once and handed out by identity. Generating here would make
        // two "defaults" unequal, break isDefault, and churn the defaults twin on every load.
        KnobSet set = set();
        assertEquals("", set.defaults().s(Mixed.SECRET));
        assertTrue(set.defaults().isDefault(Mixed.SECRET));
        assertSame(set.defaults(), set.defaults());
    }

    @Test
    @DisplayName("materialise fills an empty key and leaves a set one alone")
    void materialiseGeneratesOnce() {
        KnobSet set = set();
        ConfigValues filled = Keys.materialise(set.defaults());
        assertTrue(Keys.wellFormed(filled.s(Mixed.SECRET)));
        assertFalse(filled.isDefault(Mixed.SECRET));

        // Idempotent by identity, which is what tells ConfigFile the file needs no rewrite.
        assertSame(filled, Keys.materialise(filled));
    }

    @Test
    @DisplayName("materialise returns the SAME instance when there is nothing to do")
    void materialiseIsIdentityWhenNothingMissing() {
        ConfigValues already = set().defaults().with(Mixed.SECRET, "abcdefghijklmnop");
        assertSame(already, Keys.materialise(already),
                "returning a copy would rewrite the operator's config file on every load");
    }

    @Test
    @DisplayName("empty is legal, a well-formed key is legal, anything needing escaping is not")
    void whatAKeyWillAccept() {
        assertTrue(Mixed.SECRET.acceptsText(""), "empty is how \"not generated yet\" is spelled");
        assertTrue(Mixed.SECRET.acceptsText("abcdefghijklmnop"));
        assertFalse(Mixed.SECRET.acceptsText("short"), "below the generated length");
        assertFalse(Mixed.SECRET.acceptsText("abcdefghijklmno!"), "punctuation would need escaping");
        assertFalse(Mixed.SECRET.acceptsText("abcdefghijklmn op"), "a space would break the URL");
        assertFalse(Mixed.SECRET.acceptsText("x".repeat(65)), "past the ceiling");
    }

    @Test
    @DisplayName("a bad key in a hand-edited file falls back to empty, so the next load regenerates")
    void badKeyDegradesToRegeneration() {
        // The whole point of empty being legal: a mangled key must not wedge the server, and
        // "" is the state that gets a fresh one made rather than a broken one kept.
        KnobSet set = set();
        ConfigValues.Loaded loaded =
                ConfigValues.from(set, Map.of(), Map.of(Mixed.SECRET, "not a valid key!"));
        assertFalse(loaded.clean());
        assertEquals("", loaded.config().s(Mixed.SECRET));
        assertTrue(Keys.wellFormed(Keys.materialise(loaded.config()).s(Mixed.SECRET)));
    }

    @Test
    @DisplayName("clearing the key is how a leaked one is revoked")
    void clearingRegenerates() {
        ConfigValues before = Keys.materialise(set().defaults());
        String leaked = before.s(Mixed.SECRET);
        ConfigValues after = Keys.materialise(before.with(Mixed.SECRET, ""));
        assertNotEquals(leaked, after.s(Mixed.SECRET));
        assertTrue(Keys.wellFormed(after.s(Mixed.SECRET)));
    }
}
