package dev.luizloyola.anima.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesKnobs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the GUI's display labels in step with what there is to label — Anima's own {@link Knob}s
 * and the {@link ProfileAspect} schema every consumer's generated species knobs point at. Drift is
 * silent otherwise: the screen reads through {@code translatableWithFallback}, so a missing entry
 * degrades to a prettified key and a renamed knob leaves a dead string behind.
 *
 * <p>Aspects are labelled once, here, for everybody — every species' generated knob points at
 * {@code anima.config.aspect.*} — so this covers the schema, not any one mod's knob keys.
 *
 * <p>Keys are scraped with a regex, not parsed: no JSON library, no Minecraft on the classpath.
 * Key names only, never values, so it has no opinion on the English wording.
 */
class ConfigLabelsTest {

    private static final String LANG = "/assets/anima/lang/en_us.json";
    private static final String OPTION_PREFIX = "anima.config.option.";
    private static final String ASPECT_PREFIX = SpeciesKnobs.LANG_ROOT;
    private static final String CATEGORY_PREFIX = "anima.config.category.";

    /** Not a knob: the open-keyed danger table's own "everything else" row. */
    private static final String DANGER_DEFAULT_ROW = OPTION_PREFIX + "danger.default_weight";

    @Test
    @DisplayName("every knob has a label, and every label belongs to a knob")
    void labelsAndKnobsAgree() {
        Set<String> expected = new TreeSet<>();
        for (Knob knob : Knob.values()) {
            expected.add(OPTION_PREFIX + knob.key());
        }

        Set<String> actual = new TreeSet<>();
        for (String key : langKeys()) {
            // The danger table's "everything else" row is not a knob (open key set) and its label
            // is optional — the screen falls back for it like any other.
            if (key.startsWith(OPTION_PREFIX) && !key.endsWith(".desc")
                    && !key.equals(DANGER_DEFAULT_ROW)) {
                actual.add(key);
            }
        }

        assertEquals(expected, actual,
                "en_us.json option labels have drifted from Knob — add the missing keys, "
                        + "or delete the ones whose knob is gone");
    }

    @Test
    @DisplayName("every aspect has a label — one word for it, shared by every species everywhere")
    void labelsAndAspectsAgree() {
        Set<String> expected = new TreeSet<>();
        for (ProfileAspect aspect : ProfileAspect.all()) {
            expected.add(ASPECT_PREFIX + aspect.key());
        }

        Set<String> actual = new TreeSet<>();
        for (String key : langKeys()) {
            if (key.startsWith(ASPECT_PREFIX) && !key.endsWith(".desc")) {
                actual.add(key);
            }
        }

        assertEquals(expected, actual,
                "en_us.json aspect labels have drifted from ProfileAspect — a generated species "
                        + "knob in ANY consumer reads its label from here, so a missing one shows "
                        + "as a raw key on somebody else's screen");
    }

    @Test
    @DisplayName("every category a tab could be built from has a name")
    void categoriesAreNamed() {
        Set<String> expected = new TreeSet<>();
        for (Knob knob : Knob.values()) {
            expected.add(CATEGORY_PREFIX + knob.section());
        }
        // A generated family groups by species and by the aspect's own section. The species half of
        // that tab name is the consumer's word for its own body; this half is ours.
        for (ProfileAspect aspect : ProfileAspect.all()) {
            expected.add(CATEGORY_PREFIX + aspect.section());
        }

        Set<String> actual = new TreeSet<>();
        for (String key : langKeys()) {
            if (key.startsWith(CATEGORY_PREFIX)) {
                actual.add(key);
            }
        }

        assertEquals(expected, actual, "en_us.json category names have drifted");
    }

    @Test
    @DisplayName("an optional .desc override, if present, must name something that still exists")
    void descriptionOverridesNameRealThings() {
        // Descriptions fall back to doc(), so these are optional by design — but a .desc for
        // something that no longer exists is a string nobody will ever see again.
        for (String key : langKeys()) {
            if (!key.endsWith(".desc")) {
                continue;
            }
            String owner = key.substring(0, key.length() - ".desc".length());
            if (owner.startsWith(ASPECT_PREFIX)) {
                assertTrue(ProfileAspect.byKey(owner.substring(ASPECT_PREFIX.length())).isPresent(),
                        "no aspect matches the description override " + key);
            } else if (owner.startsWith(OPTION_PREFIX)) {
                assertTrue(Knob.byKey(owner.substring(OPTION_PREFIX.length())).isPresent()
                                || owner.equals(DANGER_DEFAULT_ROW),
                        "no knob matches the description override " + key);
            }
        }
    }

    @Test
    @DisplayName("labels are not just the raw key echoed back")
    void labelsAreActuallyWritten() {
        // A label identical to its leaf means someone added the key to silence this test's siblings
        // without writing a label; the fallback would have produced the same thing for free.
        for (Knob knob : Knob.values()) {
            assertLabelSaysSomething(OPTION_PREFIX + knob.key(), knob.key(), knob.leaf());
        }
        for (ProfileAspect aspect : ProfileAspect.all()) {
            assertLabelSaysSomething(ASPECT_PREFIX + aspect.key(), aspect.key(),
                    aspect.key().substring(aspect.key().indexOf('.') + 1));
        }
    }

    private static void assertLabelSaysSomething(String langKey, String key, String leaf) {
        Matcher m = Pattern.compile(
                Pattern.quote("\"" + langKey + "\"") + "\\s*:\\s*\"([^\"]*)\"").matcher(langSource());
        assertTrue(m.find(), "no label found for " + key);
        String label = m.group(1);
        assertFalse(label.isBlank(), key + " has a blank label");
        assertFalse(label.equals(key) || label.equals(leaf),
                key + " label is just the key — write one or drop the entry and let "
                        + "the fallback handle it");
    }

    private static Set<String> langKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(langSource());
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertFalse(keys.isEmpty(), LANG + " yielded no keys — did the file move?");
        return keys;
    }

    private static String langSource() {
        try (InputStream in = ConfigLabelsTest.class.getResourceAsStream(LANG)) {
            assertNotNull(in, "missing resource " + LANG);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + LANG, e);
        }
    }
}
