package dev.luizloyola.anima.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The mod jar this build just produced, opened for inspection.
 *
 * <p>The expensive mistakes live at the jar's edge, invisible in {@code src/} and fatal to no
 * compile: an unpackaged licence, a mixin config naming a class that is gone, Autarkia pinning an
 * Anima version it was not built against, a shaded-in dependency.
 *
 * <p>The path is handed in from {@code tasks.jar}, never globbed: {@code build/libs/} keeps
 * hundreds of timestamped jars, so "the newest match" is a guess. JDK zip only, no JSON library —
 * these fixtures are shared with consumers; {@code fabric.mod.json} is left to each mod's own test,
 * where Minecraft has already put Gson on the classpath.
 */
public final class ModJar {

    private final Path path;
    private final Set<String> entries;

    private ModJar(Path path, Set<String> entries) {
        this.path = path;
        this.entries = entries;
    }

    /**
     * The jar at the absolute path in system property {@code key}.
     *
     * @throws IllegalStateException if the property is unset or the file is missing — the test task
     *     declares a dependency on {@code jar}, so either means the wiring broke rather than the
     *     jar being legitimately absent.
     */
    public static ModJar fromSystemProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "system property " + key + " is unset — the test task must pass the jar built "
                            + "by `tasks.jar` (see the jvmArgumentProviders line in build.gradle.kts)");
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(key + " points at " + path + ", which is not a file");
        }
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (ZipEntry e : zip.stream().toList()) {
                if (!e.isDirectory()) {
                    names.add(e.getName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(path + " is an empty jar");
        }
        return new ModJar(path, names);
    }

    /** Every file entry, directories excluded, in sorted order. */
    public Set<String> entries() {
        return entries;
    }

    /** Whether the jar carries this exact path. */
    public boolean has(String entry) {
        return entries.contains(entry);
    }

    /** Entries that do not sit under any of {@code prefixes}. */
    public List<String> entriesOutside(List<String> prefixes) {
        return entries.stream()
                .filter(e -> prefixes.stream().noneMatch(e::startsWith))
                .toList();
    }

    /**
     * The text of one entry.
     *
     * @throws IllegalStateException if it is missing, so a caller never silently checks nothing.
     */
    public String text(String entry) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            if (found == null) {
                throw new IllegalStateException(path.getFileName() + " has no entry " + entry);
            }
            try (var in = zip.getInputStream(found)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + entry + " from " + path, e);
        }
    }

    /** The jar's file name, for messages. */
    public String name() {
        return path.getFileName().toString();
    }
}
