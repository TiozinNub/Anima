package dev.luizloyola.anima.mod.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.luizloyola.anima.core.config.ConfigValues;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.core.config.KnobSet;
import dev.luizloyola.anima.core.config.KnobSpec;
import dev.luizloyola.anima.core.config.ConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The JSON face of {@link ConfigValues} — {@code config/<mod id>.json}, read at startup and on
 * {@code /" + set.id() + " config reload}, written back whenever a knob changes.
 *
 * <p>Derived from the {@link KnobSet}: the file's shape is the knobs' dotted keys, one object per
 * segment, so a new tunable appears with no code change here and a generated species aspect
 * ({@code person.anima_settings.senses.radius}) nests as far as it needs to.
 *
 * <p><b>An open-keyed table is not this:</b> entity ids are an open set, so the flee weights are
 * their own artifact — see {@code DangerFile}.
 *
 * <p><b>Self-documenting:</b> each value is preceded by a {@code "// name"} string holding the
 * knob's doc sentence — a {@code //} key is skipped on read, which buys an explained file with a
 * dependency-free parser — refreshed from the code on every write.
 *
 * <p><b>Nothing here throws:</b> a missing file is written from the defaults; a malformed one is
 * reported and the defaults used, with the operator's file left untouched. Only {@link #save}
 * surfaces I/O failure, as a log line.
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

    /** The file's base name, e.g. {@code anima.json}. */
    private String fileName() {
        return set.fileName();
    }
    /** Prefix marking a generated documentation line rather than a value. */
    private static final String DOC_PREFIX = "// ";

    /** {@code <game dir>/config/<mod id>.json}. */
    public Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(fileName());
    }

    /**
     * Reads the file and {@linkplain Config#install installs} what it found — the one call both
     * startup and {@code /" + set.id() + " config reload} make. Returns the human-readable notes worth
     * showing (clamped values, unparseable entries, unknown keys); empty means a clean load.
     * Creates the file from the defaults when it does not exist yet.
     */
    public List<String> reload() {
        Path path = path();
        if (!Files.exists(path)) {
            store.install(set.defaults());
            save(set.defaults());
            AnimaMod.LOGGER.info("{} config: wrote defaults to {}", set.title(), path);
            return List.of();
        }

        JsonObject root;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return failed(path, "top level is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException e) {
            return failed(path, "could not be read (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            // Gson throws JsonSyntaxException/JsonParseException — both unchecked.
            return failed(path, "is not valid JSON (" + e.getMessage() + ")");
        }

        List<String> problems = new ArrayList<>();
        Map<KnobSpec, Double> supplied = new java.util.LinkedHashMap<>();
        for (KnobSpec knob : set.knobs()) {
            JsonElement value = lookup(root, knob);
            if (value == null) {
                continue; // absent: the default stands, silently — that is what a fresh file means
            }
            Double read = asNumber(value, knob);
            if (read == null) {
                problems.add(knob.key() + ": expected " + knob.expects()
                        + ", found " + value + " — using " + knob.format(knob.def()));
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
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(fileName() + ".tmp");
            Files.writeString(tmp, render(config), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            AnimaMod.LOGGER.error("{} config: could not write {}", set.title(), path, e);
            return false;
        }
    }

    /** The exact text {@link #save} writes — pulled out so a test can check it round-trips. */
    public String render(ConfigValues config) {
        JsonObject root = new JsonObject();
        for (KnobSpec knob : set.knobs()) {
            List<String> path = knob.path();
            JsonObject holder = root;
            for (int i = 0; i < path.size() - 1; i++) {
                JsonObject nested = holder.getAsJsonObject(path.get(i));
                if (nested == null) {
                    nested = new JsonObject();
                    holder.add(path.get(i), nested);
                }
                holder = nested;
            }
            String leaf = path.get(path.size() - 1);
            holder.addProperty(DOC_PREFIX + leaf, knob.doc());
            holder.add(leaf, toJson(knob, config.get(knob)));
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                + System.lineSeparator();
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

    /** Walks a knob's dotted path down the tree; null the moment a level is missing or not an object. */
    private static JsonElement lookup(JsonObject root, KnobSpec knob) {
        List<String> path = knob.path();
        JsonObject holder = root;
        for (int i = 0; i < path.size() - 1; i++) {
            JsonElement nested = holder.get(path.get(i));
            if (nested == null || !nested.isJsonObject()) {
                return null;
            }
            holder = nested.getAsJsonObject();
        }
        return holder.get(path.get(path.size() - 1));
    }

    /** The stored double for a JSON value, or null when it isn't the knob's kind. */
    private static Double asNumber(JsonElement value, KnobSpec knob) {
        if (!value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (knob.kind() == KnobSpec.Kind.BOOL) {
            return primitive.isBoolean() ? (primitive.getAsBoolean() ? 1.0 : 0.0) : null;
        }
        if (!primitive.isNumber()) {
            return null;
        }
        double raw = primitive.getAsDouble();
        if (!Double.isFinite(raw)) {
            return null;
        }
        // An int knob given 12.5 is a mistake worth naming, not silently rounding.
        return knob.kind() == KnobSpec.Kind.INT && raw != Math.rint(raw) ? null : raw;
    }

    private static JsonElement toJson(KnobSpec knob, double value) {
        switch (knob.kind()) {
            case BOOL:
                return new JsonPrimitive(value != 0.0);
            case INT:
                return new JsonPrimitive((long) value);
            case DOUBLE:
            default:
                return new JsonPrimitive(value);
        }
    }

    /**
     * Names entries the mod does not recognise — almost always a typo or a knob an update removed,
     * and either way the operator thinks they set something they did not.
     */
    private List<String> unknownKeys(JsonObject root) {
        Set<String> leaves = new LinkedHashSet<>();
        Set<String> branches = new LinkedHashSet<>();
        for (KnobSpec knob : set.knobs()) {
            leaves.add(knob.key());
            List<String> path = knob.path();
            // Every proper prefix is an object the file is allowed to contain.
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
     * hand-written knob, four for a generated species aspect — so this follows the tree rather
     * than assuming a section and a leaf.
     */
    private static void walk(JsonObject object, String prefix, Set<String> leaves,
            Set<String> branches, List<String> problems) {
        for (String name : object.keySet()) {
            if (name.startsWith(DOC_PREFIX.trim())) {
                continue; 
            }
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            JsonElement value = object.get(name);
            if (leaves.contains(key)) {
                continue; // a knob, already read by lookup()
            }
            if (branches.contains(key)) {
                if (!value.isJsonObject()) {
                    problems.add(String.format(Locale.ROOT,
                            "\"%s\" should be an object — ignored", key));
                    continue;
                }
                walk(value.getAsJsonObject(), key, leaves, branches, problems);
                continue;
            }
            // Depth matters to whoever has to fix it: a stray top-level object is usually a
            // section this version renamed or dropped, and saying "section" tells them to look
            // for where the whole group went rather than for a typo in one line.
            problems.add(String.format(Locale.ROOT, "unknown %s \"%s\" — ignored",
                    prefix.isEmpty() ? "section" : "key", key));
        }
    }
}
