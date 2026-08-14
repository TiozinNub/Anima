package dev.luizloyola.anima.mod.config;

import dev.luizloyola.anima.mod.AnimaMod;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The reference twin of a config file — {@code config/<name>.defaults.toml}, regenerated every
 * time the mod loads and read back by nobody.
 *
 * <p>It answers "what have I changed", which the live file cannot: a number reads the same whether
 * the mod shipped it or somebody typed it once and forgot.
 *
 * <pre>{@code diff config/anima.defaults.toml config/anima.toml}</pre>
 *
 * <p>Every line that comes back is a decision — including a default that MOVED in an update while
 * the operator's file quietly pins the old one.
 *
 * <p><b>Nothing reads it back.</b> Editing it changes nothing and the next load overwrites it; a
 * defaults file the mod consulted would be a second place for the truth to live.
 *
 * <p>Failure here is a footnote: a config load must not fall over because the commentary beside it
 * could not be written.
 */
public final class DefaultsFile {

    private static final String SUFFIX = ".toml";
    private static final String MARK = ".defaults";

    private DefaultsFile() {
    }

    /** {@code <name>.toml} → {@code <name>.defaults.toml}, in the same directory. */
    public static Path beside(Path live) {
        String name = live.getFileName().toString();
        String base = name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length())
                : name;
        return live.resolveSibling(base + MARK + SUFFIX);
    }

    /**
     * Writes the twin of {@code live}, {@code rendered} being that file's content with nothing
     * changed — the same renderer, so a fresh install diffs clean.
     *
     * <p>{@code tag} is who to blame in the log ({@code "Anima config"}).
     */
    public static void write(Path live, String rendered, String tag) {
        Path path = beside(live);
        try {
            if (TomlDocument.saveIfChanged(path, header(live) + rendered)) {
                AnimaMod.LOGGER.info("{}: {} changed — rewrote it", tag, path.getFileName());
            }
        } catch (IOException e) {
            AnimaMod.LOGGER.warn("{}: could not write {} ({}) — the live file is unaffected",
                    tag, path, TomlDocument.problem(e));
        }
    }

    /**
     * The block at the top saying what this file is and how to use it — and the only thing a fresh
     * install's diff reports, which the text itself admits.
     */
    private static String header(Path live) {
        String liveName = live.getFileName().toString();
        String twinName = beside(live).getFileName().toString();
        return TomlDocument.header(
                twinName + " — " + liveName + " as it stands with nothing changed. Regenerated "
                        + "every time the mod loads, so it always describes THIS build.\n\n"
                        + "NOTHING READS THIS FILE. Editing it changes nothing and the next load "
                        + "overwrites it. It is here so you can ask what you have changed, which "
                        + "the file beside it cannot answer on its own:\n\n"
                        + "diff " + twinName + " " + liveName + "\n\n"
                        + "Every line that comes back is a decision somebody made — including a "
                        + "default that moved in an update and left your file pinning the old "
                        + "one. On an untouched install the only difference is this comment.");
    }
}
