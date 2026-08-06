package dev.luizloyola.anima.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A mod's own Java source, read as text, so a test can assert the SHAPE of the codebase: which
 * layer may name which package, and where a Stonecutter comment may live — CLAUDE.md rules that
 * nothing enforced.
 *
 * <p><b>Source, not bytecode</b>, hence hand-rolled rather than an ArchUnit dependency: a
 * Stonecutter directive is a COMMENT, and an unused import — the one real violation this found —
 * leaves no trace either, so neither can be asked of a class file. Text also means code inside a
 * DISABLED Stonecutter branch is checked on every node.
 *
 * <p>In Anima's fixtures, not copied per mod: the rules are the same for every consumer, and only
 * the rule TABLE is per mod. Always the BRANCH source ({@code <mod>/src/main/java}), never a node's
 * generated copy — one source of truth, a violation fails every node, and the Stonecutter rule can
 * only be asked of source that still has its directives.
 */
public final class SourceTree {

    private final Path root;
    private final List<JavaSource> files;

    private SourceTree(Path root, List<JavaSource> files) {
        this.root = root;
        this.files = files;
    }

    /**
     * The tree at the absolute path in system property {@code key}, set by the build script — see
     * the {@code systemProperty} line in each mod's {@code test} task.
     *
     * @throws IllegalStateException if the property is unset or names no directory. A silently
     *     empty tree would turn every rule into a test that passes by finding nothing, which is
     *     the one failure mode a checker must not have.
     */
    public static SourceTree fromSystemProperty(String key) {
        String path = System.getProperty(key);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "system property " + key + " is unset — the test task must pass the branch "
                            + "source root (see `systemProperty` in the mod's build.gradle.kts)");
        }
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(key + " points at " + root + ", which is not a directory");
        }
        return rootedAt(root);
    }

    /**
     * Every {@code .java} file under {@code root}. Separate from
     * {@link #fromSystemProperty(String)} so the scanner can be tested against a hand-built tree —
     * a checker whose own logic is unverified is a checker that passes by finding nothing.
     */
    public static SourceTree rootedAt(Path root) {
        List<JavaSource> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(p -> found.add(
                            new JavaSource(root.relativize(p).toString().replace('\\', '/'), read(p))));
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + root, e);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("no .java files under " + root + " — did the source move?");
        }
        return new SourceTree(root, List.copyOf(found));
    }

    public List<JavaSource> all() {
        return files;
    }

    /**
     * Every source file in {@code packageName} or below — a layer, named the way the code names it
     * ({@code "dev.luizloyola.anima.core"}).
     *
     * @throws IllegalStateException if the package holds no files, so a rename that empties a layer
     *     fails loudly instead of quietly retiring the rule that guarded it.
     */
    public List<JavaSource> inPackage(String packageName) {
        String prefix = packageName.replace('.', '/') + "/";
        List<JavaSource> found = files.stream().filter(f -> f.path().startsWith(prefix)).toList();
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "no source under " + packageName + " in " + root + " — the package moved, and "
                            + "the rule that guarded it is now guarding nothing");
        }
        return found;
    }

    /** Where this tree was read from, for failure messages. */
    public Path root() {
        return root;
    }

    /** Formats violations one per line, so a failure names every offender instead of only the first. */
    public static String report(String headline, List<String> violations) {
        StringBuilder out = new StringBuilder(headline)
                .append(" — ").append(violations.size()).append(" violation(s):");
        for (String v : violations) {
            out.append("\n  ").append(v);
        }
        return out.toString();
    }

    /** A violation line naming the file and the source line it sits on. */
    public static String at(JavaSource file, Line line) {
        return file.path() + ":" + line.number() + "  " + line.text();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** One line of one file: where a violation is, and what it says. */
    public record Line(int number, String text) {
        @Override
        public String toString() {
            return number + ": " + text;
        }
    }

    /** One source file: its raw text, its text with the comments taken out, and where to look. */
    public static final class JavaSource {

        /**
         * A Stonecutter directive, matched only where one can actually appear: opening a line, or
         * closing a commented-out branch after a {@code *}{@code /}. Not matched
         * mid-line, so that {@code compat/package-info.java} — which documents this very rule by
         * quoting a directive inside javadoc — is not read as breaking it.
         */
        private static final Pattern DIRECTIVE =
                Pattern.compile("^[ \\t]*(?:\\*/[ \\t]*)?(?://[?$^]|/\\*[?$^])");

        private final String path;
        private final String text;
        private String code;

        JavaSource(String path, String text) {
            this.path = path;
            this.text = text;
        }

        /** Path relative to the source root, e.g. {@code dev/luizloyola/anima/core/agent/Metabolism.java}. */
        public String path() {
            return path;
        }

        /** The file exactly as written — the only view in which a Stonecutter directive still exists. */
        public String text() {
            return text;
        }

        /**
         * The file with every comment and string literal blanked to spaces, newlines kept so line
         * numbers still line up. This is the view a dependency rule must use: {@code core/} is full
         * of prose promising it never imports {@code net.minecraft}, and a checker that read the
         * prose would fail eight files for keeping their word.
         */
        public String code() {
            if (code == null) {
                code = blankCommentsAndLiterals(text);
            }
            return code;
        }

        /**
         * Every line of {@link #code()} that names {@code prefix} — an import or a fully-qualified
         * use, which is one question because they are the same dependency and this codebase writes
         * both ({@code new dev.luizloyola.anima.mod.brain.BeingSense(this)}, in Person).
         */
        public List<Line> mentions(String prefix) {
            return linesMatching(code(), line -> line.contains(prefix));
        }

        /** Every Stonecutter directive line, read from the raw text. */
        public List<Line> directives() {
            return linesMatching(text, line -> DIRECTIVE.matcher(line).find());
        }

        /**
         * Lines of {@code searched} that {@code keep} accepts, reported with the text of the same
         * line of the raw file — a hit found in {@link #code()} still reads as what was written,
         * blanks and all being unhelpful in a failure message.
         */
        private List<Line> linesMatching(String searched, Predicate<String> keep) {
            String[] lines = searched.split("\n", -1);
            String[] raw = text.split("\n", -1);
            List<Line> found = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (keep.test(lines[i])) {
                    found.add(new Line(i + 1, (i < raw.length ? raw[i] : lines[i]).strip()));
                }
            }
            return found;
        }

        /**
         * Blanks comments and string literals, keeping every newline so line numbers survive.
         *
         * <p>Hand-written because the alternative is a parser dependency for forty lines of state
         * machine. It handles the four things that can hide a {@code //}: line comments, block
         * comments, ordinary literals with their escapes, and text blocks — which this repo's
         * tests use, and which a naive scanner reads as an empty string followed by garbage.
         */
        private static String blankCommentsAndLiterals(String s) {
            StringBuilder out = new StringBuilder(s.length());
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    while (i < n && s.charAt(i) != '\n') {
                        out.append(' ');
                        i++;
                    }
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    out.append("  ");
                    i += 2;
                    while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                        out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    if (i < n) {
                        out.append("  ");
                        i += 2;
                    }
                } else if (s.startsWith("\"\"\"", i)) {
                    out.append("   ");
                    i += 3;
                    // A backslash consumes whatever follows — including the newline it hides, which
                    // is why every skipped character goes through blank() and line numbers hold.
                    while (i < n && !s.startsWith("\"\"\"", i)) {
                        int step = s.charAt(i) == '\\' ? 2 : 1;
                        for (int k = 0; k < step && i < n; k++, i++) {
                            blank(out, s.charAt(i));
                        }
                    }
                    if (i < n) {
                        out.append("   ");
                        i += 3;
                    }
                } else if (c == '"' || c == '\'') {
                    out.append(' ');
                    i++;
                    while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') {
                        int step = s.charAt(i) == '\\' ? 2 : 1;
                        for (int k = 0; k < step && i < n; k++, i++) {
                            blank(out, s.charAt(i));
                        }
                    }
                    if (i < n && s.charAt(i) == c) {
                        out.append(' ');
                        i++;
                    }
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        private static void blank(StringBuilder out, char c) {
            out.append(c == '\n' ? '\n' : ' ');
        }
    }
}
