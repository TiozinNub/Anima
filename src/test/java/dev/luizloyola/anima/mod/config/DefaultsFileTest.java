package dev.luizloyola.anima.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The defaults twin's two silent failure modes: both pass a build and surface months later, when
 * "what have I changed" gets an answer that is subtly not that. Nothing here names a Minecraft
 * type — the twin is text beside text.
 */
class DefaultsFileTest {

    @Test
    @DisplayName("the twin sits beside its file, named for it")
    void namedForItsFile() {
        assertEquals("anima.defaults.toml",
                DefaultsFile.beside(Path.of("config", "anima.toml")).getFileName().toString());
        assertEquals("autarkia-danger.defaults.toml",
                DefaultsFile.beside(Path.of("config", "autarkia-danger.toml"))
                        .getFileName().toString());
        assertEquals(Path.of("config"),
                DefaultsFile.beside(Path.of("config", "anima.toml")).getParent());
        // The mod loads config/<id>.toml by name, so the twin must not be able to answer to one:
        // a twin called anima.toml.defaults would be fine, anima.toml would be a mod that reads
        // its own commentary back as settings.
        assertFalse(DefaultsFile.beside(Path.of("anima.toml")).getFileName().toString()
                .equals("anima.toml"));
    }

    @Test
    @DisplayName("the header does not stop the twin being TOML")
    void headerParses(@TempDir Path dir) throws IOException {
        // Somebody will eventually parse the twin. The header is prepended as raw text —
        // night-config attaches comments to entries, and a file-level line belongs to none — which
        // works until it is one character off the comment syntax.
        Path live = dir.resolve("anima.toml");
        CommentedConfig body = TomlDocument.document();
        body.set(List.of("limits", "reads_per_tick"), 256L);
        body.setComment(List.of("limits", "reads_per_tick"), TomlDocument.comment("How many."));
        DefaultsFile.write(live, TomlDocument.render(body), "test");

        Path twin = DefaultsFile.beside(live);
        assertTrue(Files.exists(twin), "no twin was written");
        String text = Files.readString(twin, StandardCharsets.UTF_8);
        Number read = TomlDocument.parse(text).get(List.of("limits", "reads_per_tick"));
        assertEquals(256L, read.longValue());
        assertTrue(text.contains("diff anima.defaults.toml anima.toml"),
                "the header should say how to use the file, got:\n" + text);
    }

    @Test
    @DisplayName("the body is byte-identical to the file it is a twin of")
    void bodyMatchesTheLiveRender(@TempDir Path dir) throws IOException {
        // An untouched install must diff clean. A twin rendered by a second code path would agree
        // today and drift on the first change — and drift reads as "you changed something" forever.
        Path live = dir.resolve("anima.toml");
        CommentedConfig body = TomlDocument.document();
        body.set(List.of("brain", "stickiness"), 0.25);
        String rendered = TomlDocument.render(body);
        TomlDocument.save(live, rendered);
        DefaultsFile.write(live, rendered, "test");

        String twin = Files.readString(DefaultsFile.beside(live), StandardCharsets.UTF_8);
        assertTrue(twin.endsWith(rendered),
                "the twin's body should be exactly the live render, got:\n" + twin);
        // ...and the header is the only thing a diff of the pair reports, which the header says.
        assertEquals(rendered, twin.substring(twin.length() - rendered.length()));
    }

    @Test
    @DisplayName("an unchanged twin is left alone")
    void unchangedIsNotRewritten(@TempDir Path dir) throws IOException {
        // A file regenerated on every launch whose content only moves when the code does. Leaving
        // it alone is what makes its mtime mean "the defaults last changed here" rather than
        // "somebody started the game".
        Path path = dir.resolve("x.toml");
        assertTrue(TomlDocument.saveIfChanged(path, "a = 1\n"), "a missing file must be written");
        assertFalse(TomlDocument.saveIfChanged(path, "a = 1\n"), "identical text was rewritten");
        assertTrue(TomlDocument.saveIfChanged(path, "a = 2\n"), "changed text was not written");
        assertEquals("a = 2\n", Files.readString(path, StandardCharsets.UTF_8));
    }
}
