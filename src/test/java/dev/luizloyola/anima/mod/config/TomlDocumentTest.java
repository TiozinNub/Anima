package dev.luizloyola.anima.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the TOML settings chosen on top of night-config, not night-config itself: each has a
 * default that is wrong for this file and wrong in a way nothing else checks — the build passes
 * and the damage turns up later in a config file.
 *
 * <p>Headless: names no Minecraft type, only {@link TomlDocument} and the parser under it.
 */
class TomlDocumentTest {

    @Test
    @DisplayName("keys come out in the order they went in")
    void orderIsPreserved() {
        // night-config defaults to a HashMap. Knob order is deliberate and the doc sentences lean
        // on it ("columns wait in the queue below"), so a shuffled file makes the docs wrong.
        CommentedConfig config = TomlDocument.document();
        List<String> written = List.of("zebra", "alpha", "middle", "beta", "yankee");
        for (String key : written) {
            config.set(List.of("section", key), 1L);
        }

        List<String> seen = new ArrayList<>();
        for (String line : TomlDocument.render(config).split("\n")) {
            int eq = line.indexOf(" = ");
            if (eq > 0) {
                seen.add(line.substring(0, eq));
            }
        }
        assertEquals(written, seen, "the document did not keep insertion order — it is probably "
                + "no longer backed by a LinkedHashMap");
    }

    @Test
    @DisplayName("nested tables are not indented, however deep the key")
    void deepTablesStayFlat() {
        // The default indents each nested table one level further than the last, which walks a
        // four-deep generated species key steadily off the right of the screen.
        CommentedConfig config = TomlDocument.document();
        config.set(List.of("person", "anima_settings", "needs", "hunger", "starving"), 0.5);
        config.set(List.of("limits", "reads_per_tick"), 256L);

        String rendered = TomlDocument.render(config);
        assertTrue(rendered.contains("[person.anima_settings.needs.hunger]"),
                "a deep key should collapse into one flat table header, got:\n" + rendered);
        for (String line : rendered.split("\n")) {
            assertFalse(line.startsWith(" ") || line.startsWith("\t"),
                    "no line should be indented, got: \"" + line + "\"");
        }
    }

    @Test
    @DisplayName("a whole number stays whole and a fraction stays a fraction")
    void numberKindsSurviveTheRoundTrip() {
        // Not cosmetic: an INT knob handed 12.5 is a type the parser hands back, rather than a
        // fraction we have to go looking for.
        CommentedConfig config = TomlDocument.document();
        config.set(List.of("t", "whole"), 256L);
        config.set(List.of("t", "fraction"), 0.75);
        config.set(List.of("t", "flag"), false);

        CommentedConfig back = TomlDocument.parse(TomlDocument.render(config));
        assertInstanceOf(Integer.class, back.get(List.of("t", "whole")),
                "256 came back as something other than an integer");
        assertInstanceOf(Double.class, back.get(List.of("t", "fraction")));
        assertInstanceOf(Boolean.class, back.get(List.of("t", "flag")));
    }

    @Test
    @DisplayName("an entity id keeps its namespace through a round trip")
    void namespacedKeysSurvive() {
        // The danger file's keys are an open set, and a modded one is "namespace:path" — not a
        // bare TOML key. Quote on the way out, unquote back, or modded overrides silently stop.
        CommentedConfig config = TomlDocument.document();
        config.set(List.of("overrides", "somemod:dire_wolf"), 2.5);
        config.set(List.of("overrides", "creeper"), 1.4);

        String rendered = TomlDocument.render(config);
        assertTrue(rendered.contains("\"somemod:dire_wolf\" = 2.5"),
                "a namespaced key was not quoted, so the file is not valid TOML:\n" + rendered);

        CommentedConfig back = TomlDocument.parse(rendered);
        assertEquals(2.5, back.get(List.of("overrides", "somemod:dire_wolf")));
        assertEquals(1.4, back.get(List.of("overrides", "creeper")));
    }

    @Test
    @DisplayName("a doc sentence wraps, and a blank line in it survives")
    void commentsWrapAndKeepParagraphs() {
        String doc = "One two three four five six seven eight nine ten eleven twelve thirteen "
                + "fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one.\n\n"
                + "A second paragraph.";
        String comment = TomlDocument.comment(doc);

        List<String> lines = List.of(comment.split("\n", -1));
        assertTrue(lines.size() >= 4, "expected wrapping plus a blank line, got: " + lines);
        assertTrue(lines.contains(""), "the blank line between paragraphs was swallowed — a doc "
                + "string's own newlines have to survive, or the wrap eats the paragraph break");
        for (String line : lines) {
            assertTrue(line.isEmpty() || line.startsWith(" "),
                    "every non-blank line needs its leading space so night-config writes \"# x\" "
                            + "rather than \"#x\", got: \"" + line + "\"");
            assertTrue(line.length() <= 98, "line ran past the wrap column: \"" + line + "\"");
        }

        CommentedConfig config = TomlDocument.document();
        config.set(List.of("t", "k"), 1L);
        config.setComment(List.of("t", "k"), comment);
        String rendered = TomlDocument.render(config);
        assertTrue(rendered.contains("\n#\n"), "the blank paragraph line should render as a bare "
                + "\"#\", got:\n" + rendered);
    }

    @Test
    @DisplayName("a parse failure reads as one whole line")
    void problemEscapesRatherThanTruncates() {
        // night-config QUOTES the offending character, so a table header missing its "]" — the
        // commonest error — carries a real newline. Cutting at the first line leaves an operator
        // "Invalid separator '".
        RuntimeException thrown = null;
        try {
            TomlDocument.parse("[limits\nbroken = = 1\n");
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertTrue(thrown != null, "malformed TOML did not throw");

        String problem = TomlDocument.problem(thrown);
        assertFalse(problem.contains("\n"), "a problem must be one line: \"" + problem + "\"");
        assertTrue(problem.endsWith("in table name."), "the message was truncated at the newline "
                + "instead of escaping it, leaving nothing an operator can act on: \"" + problem + "\"");
    }
}
