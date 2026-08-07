package dev.luizloyola.anima.mod.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.NewlineStyle;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The TOML habits {@link ConfigFile} and {@code DangerFile} share — how a document is built, how
 * it is worded, and how it reaches the disk without ever being half-written.
 *
 * <p><b>Why TOML and not JSON.</b> Both files are mostly explanation, and JSON has no comments, so
 * the doc sentences were smuggled in as {@code "// name": "..."} entries the parser had to skip.
 * TOML also has integers: an {@link dev.luizloyola.anima.core.config.KnobSpec.Kind#INT} knob
 * handed {@code 12.5} comes back as a Double, where JSON's one number type meant checking by hand.
 *
 * <p><b>Order is load-bearing.</b> night-config's default config is backed by a
 * {@link java.util.HashMap} and shuffles on any JVM, while the knobs are declared in a reading
 * order their own documentation leans on ({@code reads_per_tick} says "columns wait in the queue
 * below") — so {@link #document()} backs every level with a {@link LinkedHashMap}.
 */
public final class TomlDocument {

    /**
     * Where a documentation line wraps — a fixed column, so no doc sentence is hand-wrapped in the
     * Java source, where an edit would re-wrap it wrongly.
     */
    private static final int COMMENT_WIDTH = 96;

    private TomlDocument() {
    }

    /** A new, empty document that remembers the order things are put into it. */
    public static CommentedConfig document() {
        return CommentedConfig.of(LinkedHashMap::new, TomlFormat.instance());
    }

    /**
     * Renders a document to the exact text {@link #save} writes.
     *
     * <p>Sub-tables are not indented ({@link IndentStyle#NONE}): night-config's default indents
     * each nested table one level further, which walks a four-deep species key
     * ({@code person.anima_settings.senses.radius}) off the right of the screen.
     */
    public static String render(CommentedConfig config) {
        TomlWriter writer = new TomlWriter();
        writer.setIndent(IndentStyle.NONE);
        writer.setNewline(NewlineStyle.system());
        StringWriter out = new StringWriter();
        writer.write(config, out);
        return out.toString();
    }

    /** Parses {@code text}, throwing night-config's unchecked {@code ParsingException} on garbage. */
    public static CommentedConfig parse(String text) {
        return new TomlParser().parse(new StringReader(text));
    }

    /**
     * A parser or I/O failure as one line an operator can act on.
     *
     * <p>Escaped, not truncated at the first newline: night-config QUOTES the offending character,
     * so a table header missing its {@code ]} reads {@code Invalid separator '\n' in table name.}
     * with a real newline in the quotes, and truncating would leave {@code Invalid separator '}.
     */
    public static String problem(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.replace("\\", "\\\\").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t").strip();
    }

    /**
     * Wraps a doc sentence the way night-config emits {@code #} lines: one line per newline, each
     * with a leading space so it reads {@code # like this}.
     *
     * <p>Newlines already in {@code doc} are wrapped independently — a blank line becomes a bare
     * {@code #} — and everything else is filled to {@link #COMMENT_WIDTH}.
     */
    public static String comment(String doc) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : doc.split("\n", -1)) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                lines.add(""); 
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : trimmed.split("\\s+")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > COMMENT_WIDTH) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines.stream().map(line -> line.isEmpty() ? "" : " " + line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Writes {@code text} to {@code path}, replacing it. Atomic where the filesystem allows it, so
     * a crash mid-write cannot truncate a file the mod rewrites on every {@code config set}.
     *
     * @throws IOException if the file could not be written; the caller decides how loud that is.
     */
    public static void save(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
