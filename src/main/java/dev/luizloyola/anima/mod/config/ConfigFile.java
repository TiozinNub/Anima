package dev.luizloyola.anima.mod.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.ConfigStore;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import dev.luizloyola.anima.mod.AnimaMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The TOML face of {@link ConfigValues} — {@code config/<mod id>.toml}, read at startup and on
 * {@code /<mod> config reload}, written back whenever a knob changes.
 *
 * <p>Shaped by the {@link KnobSet}: the knobs' dotted keys, one TOML table per segment, so a new
 * tunable appears without a line of code here changing.
 *
 * <p>An open-keyed table is not this — entity ids are an open set, so the flee weights are their
 * own artifact with their own lifecycle. See {@code DangerFile}.
 *
 * <p>Each value carries its knob's own doc sentence as a {@code #} comment, refreshed from the
 * code on every write; an operator's own notes do not survive one either.
 *
 * <p>Every load also writes the {@code <mod id>.defaults.toml} twin, so "what have I changed" is a
 * diff. See {@link DefaultsFile}; nothing ever reads it back.
 *
 * <p><b>Nothing here throws at the caller.</b> A missing file is written from the defaults; a
 * malformed one is reported and the defaults used, leaving the operator's file untouched. Only
 * {@link #save} surfaces I/O failure, and only as a log line.
 */
public final class ConfigFile {

    private final KnobSet set;
    private final ConfigStore store;

    /**
     * One file per knob set; a consumer constructs another for its own set and gets the same
     * atomic write, unknown-key report and doc comments.
     */
    public ConfigFile(ConfigStore store) {
        this.store = store;
        this.set = store.set();
    }

    private String fileName() {
        return set.fileName();
    }

    /** {@code <game dir>/config/<mod id>.toml}. */
    public Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(fileName());
    }

    /**
     * Reads the file and {@linkplain Config#install installs} what it found — the one call both
     * startup and {@code /<mod> config reload} make. Returns the human-readable notes worth
     * showing (clamped values, unparseable entries, unknown keys); empty means a clean load.
     * Creates the file from the defaults when it does not exist yet.
     */
    public List<String> reload() {
        Path path = path();
        // The reference twin, first and unconditionally: it is most useful when the load below
        // fails, and a file that will not parse needs an untouched one to compare against.
        DefaultsFile.write(path, render(set.defaults()), set.title() + " config");
        if (!Files.exists(path)) {
            store.install(set.defaults());
            save(set.defaults());
            AnimaMod.LOGGER.info("{} config: wrote defaults to {}", set.title(), path);
            return List.of();
        }

        CommentedConfig root;
        try {
            root = TomlDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return failed(path, "could not be read (" + TomlDocument.problem(e) + ")");
        } catch (RuntimeException e) {
            // night-config throws ParsingException — unchecked, and its message names what it
            // choked on, which is the only part of this an operator can act on.
            return failed(path, "is not valid TOML (" + TomlDocument.problem(e) + ")");
        }

        List<String> problems = new ArrayList<>();
        Map<KnobSpec, Double> supplied = new LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            Object value = lookup(root, knob);
            if (value == null) {
                continue; // absent: the default stands, silently — that is what a fresh file means
            }
            Double read = asNumber(value, knob);
            if (read == null) {
                problems.add(knob.key() + ": expected " + knob.expects()
                        + ", found " + describe(value) + " — using " + knob.format(knob.def()));
                continue;
            }
            supplied.put(knob, read);
        }
        problems.addAll(unknownKeys(root));

        ConfigValues.Loaded loaded = ConfigValues.from(set, supplied);
        problems.addAll(loaded.problems());
        store.install(loaded.config());

        for (String problem : problems) {
            AnimaMod.LOGGER.warn("{} config: {}", set.title(), problem);
        }
        return List.copyOf(problems);
    }

    /**
     * Writes {@code config} out, replacing the file. Atomic where the filesystem allows it, so a
     * crash mid-write cannot leave a truncated config behind. Returns false (and logs) on failure.
     */
    public boolean save(ConfigValues config) {
        Path path = path();
        try {
            TomlDocument.save(path, render(config));
            return true;
        } catch (IOException e) {
            AnimaMod.LOGGER.error("{} config: could not write {}", set.title(), path, e);
            return false;
        }
    }

    /** The exact text {@link #save} writes — pulled out so a test can check it round-trips. */
    public String render(ConfigValues config) {
        CommentedConfig root = TomlDocument.document();
        for (KnobSpec knob : set.knobs()) {
            List<String> path = knob.path();
            root.set(path, toToml(knob, config.get(knob)));
            root.setComment(path, TomlDocument.comment(knob.doc()));
        }
        return TomlDocument.render(root);
    }


    // --- internals -------------------------------------------------------------------------

    private List<String> failed(Path path, String why) {
        String message = fileName() + " " + why + " — falling back to defaults; "
                + "the file was left untouched so you can fix it and run "
                + "/" + set.id() + " config reload";
        AnimaMod.LOGGER.error("{} config: {} ({})", set.title(), message, path);
        store.install(set.defaults());
        return List.of(message);
    }

    /**
     * A knob's value, or null when it is absent.
     *
     * <p>The LIST form of {@code get} on purpose: the String form splits on dots itself, which
     * breaks the moment a path segment contains one.
     */
    private static Object lookup(UnmodifiableConfig root, KnobSpec knob) {
        return root.get(knob.path());
    }

    /**
     * The stored double for a parsed TOML value, or null when it isn't the knob's kind.
     *
     * <p>TOML distinguishes {@code 256} from {@code 256.0}, so this is a type check rather than the
     * range-and-fraction inspection JSON's one number type forced: a whole number arrives as an
     * Integer or a Long by magnitude (both accepted), while an INT knob given {@code 12.5} arrives
     * as a Double and is named as a mistake.
     */
    private static Double asNumber(Object value, KnobSpec knob) {
        if (knob.kind() == KnobSpec.Kind.BOOL) {
            return value instanceof Boolean b ? (b ? 1.0 : 0.0) : null;
        }
        if (value instanceof Boolean) {
            return null; // a bool where a number belongs, not "true == 1"
        }
        if (knob.kind() == KnobSpec.Kind.INT) {
            // Strict on purpose: 256.0 is accepted (it is whole), 12.5 is not.
            if (value instanceof Integer || value instanceof Long) {
                return ((Number) value).doubleValue();
            }
            if (value instanceof Number n && Double.isFinite(n.doubleValue())
                    && n.doubleValue() == Math.rint(n.doubleValue())) {
                return n.doubleValue();
            }
            return null;
        }
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        return null;
    }

    /** How a rejected value should read back to the operator, quoted the way TOML would write it. */
    private static String describe(Object value) {
        return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }

    /**
     * The value as TOML should hold it: a whole number as a TOML integer rather than
     * {@code 256.0}, so the file reads hand-written rather than dumped.
     */
    private static Object toToml(KnobSpec knob, double value) {
        return switch (knob.kind()) {
            case BOOL -> value != 0.0;
            case INT -> (long) value;
            case DOUBLE -> value;
        };
    }

    /**
     * Names entries the mod does not recognise — almost always a typo or a knob an update removed,
     * and either way the operator thinks they set something they did not.
     */
    private List<String> unknownKeys(UnmodifiableConfig root) {
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> branches = new LinkedHashSet<>();
        for (KnobSpec knob : set.knobs()) {
            leaves.add(knob.key());
            List<String> path = knob.path();
            // Every proper prefix is a table the file is allowed to contain.
            for (int i = 1; i < path.size(); i++) {
                branches.add(String.join(".", path.subList(0, i)));
            }
        }
        List<String> problems = new ArrayList<>();
        walk(root, "", leaves, branches, problems);
        return problems;
    }

    /**
     * Recursive half of {@link #unknownKeys}. Nesting is as deep as a key has dots — one for a
     * hand-written knob, four for a generated species aspect.
     */
    private static void walk(UnmodifiableConfig table, String prefix, Set<String> leaves,
            Set<String> branches, List<String> problems) {
        for (UnmodifiableConfig.Entry entry : table.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (leaves.contains(key)) {
                continue; // a knob, already read by lookup()
            }
            if (branches.contains(key)) {
                if (!(value instanceof UnmodifiableConfig sub)) {
                    problems.add(String.format(Locale.ROOT,
                            "\"%s\" should be a table — ignored", key));
                    continue;
                }
                walk(sub, key, leaves, branches, problems);
                continue;
            }
            // A stray top-level table is usually a section this version renamed or dropped, so
            // "section" points at the whole group rather than at a typo in one line.
            problems.add(String.format(Locale.ROOT, "unknown %s \"%s\" — ignored",
                    prefix.isEmpty() ? "section" : "key", key));
        }
    }
}
