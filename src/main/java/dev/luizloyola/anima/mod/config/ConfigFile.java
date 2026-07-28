package dev.luizloyola.anima.mod.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.luizloyola.anima.core.brain.instinct.Danger;
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
import java.util.EnumMap;
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
 * <p>Derived from {@link Knob}: one object per {@link Knob#section()}, one entry per
 * {@link Knob#leaf()}, so a new tunable appears with no code change here.
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
    /**
     * The per-species flee weights — not knobs (the {@link Knob} enum is closed, entity ids
     * are an open set), so this section has its own rules: every key is an entity species (or
     * {@value Danger#DEFAULT_KEY}), unknown ids are VALID (they are modded mobs, not typos),
     * and values clamp to a sane band instead of a per-knob range.
     */
    private static final String DANGER_SECTION = "danger";
    private static final double DANGER_MIN = 0.0;
    private static final double DANGER_MAX = 8.0;

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
            Danger.reset();
            save(set.defaults());
            AnimaMod.LOGGER.info("Autarkia config: wrote defaults to {}", path);
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
        problems.addAll(loadDanger(root));

        ConfigValues.Loaded loaded = ConfigValues.from(set, supplied);
        problems.addAll(loaded.problems());
        store.install(loaded.config());

        for (String problem : problems) {
            AnimaMod.LOGGER.warn("Autarkia config: {}", problem);
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
            AnimaMod.LOGGER.error("Autarkia config: could not write {}", path, e);
            return false;
        }
    }

    /** The exact text {@link #save} writes — pulled out so a test can check it round-trips. */
    public String render(ConfigValues config) {
        JsonObject root = new JsonObject();
        for (KnobSpec knob : set.knobs()) {
            JsonObject section = root.getAsJsonObject(knob.section());
            if (section == null) {
                section = new JsonObject();
                root.add(knob.section(), section);
            }
            section.addProperty(DOC_PREFIX + knob.leaf(), knob.doc());
            section.add(knob.leaf(), toJson(knob, config.get(knob)));
        }
        JsonObject danger = new JsonObject();
        danger.addProperty(DOC_PREFIX + "about",
                "Per-species flee weights. Any entity id is a valid key; mobs not listed use"
                        + " \"" + Danger.DEFAULT_KEY + "\".");
        danger.addProperty(Danger.DEFAULT_KEY, Danger.weight(Danger.DEFAULT_KEY));
        Danger.table().keySet().stream()
                .filter(species -> !species.equals(Danger.DEFAULT_KEY))
                .sorted()
                .forEach(species -> danger.addProperty(species, Danger.weight(species)));
        root.add(DANGER_SECTION, danger);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                + System.lineSeparator();
    }

    /** Reads the danger section into {@link Danger} — its open-key, clamp-only rules. */
    private static List<String> loadDanger(JsonObject root) {
        Danger.reset();
        JsonElement section = root.get(DANGER_SECTION);
        if (section == null) {
            return List.of(); // absent: the seeded defaults stand — what a fresh file means
        }
        if (!section.isJsonObject()) {
            return List.of(DANGER_SECTION + " should be an object — using the built-in weights");
        }
        List<String> problems = new ArrayList<>();
        Map<String, Double> overrides = new java.util.LinkedHashMap<>();
        for (String species : section.getAsJsonObject().keySet()) {
            if (species.startsWith(DOC_PREFIX.trim())) {
                continue;
            }
            JsonElement value = section.getAsJsonObject().get(species);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(value.getAsDouble())) {
                problems.add(DANGER_SECTION + "." + species + ": expected a number, found "
                        + value + " — ignored");
                continue;
            }
            double raw = value.getAsDouble();
            double clamped = Math.max(DANGER_MIN, Math.min(DANGER_MAX, raw));
            if (clamped != raw) {
                problems.add(String.format(Locale.ROOT,
                        "%s.%s: %s is out of range [%s, %s] — using %s", DANGER_SECTION,
                        species, raw, DANGER_MIN, DANGER_MAX, clamped));
            }
            overrides.put(species, clamped);
        }
        Danger.install(overrides);
        return problems;
    }

    /** One live weight changed from the command — installs and persists. Returns the clamped value. */
    public double setDanger(String species, double raw) {
        double clamped = Math.max(DANGER_MIN, Math.min(DANGER_MAX, raw));
        Map<String, Double> overrides = new java.util.LinkedHashMap<>(Danger.table());
        overrides.put(species, clamped);
        Danger.install(overrides);
        save(store.get());
        return clamped;
    }

    // --- internals -------------------------------------------------------------------------

    private List<String> failed(Path path, String why) {
        String message = fileName() + " " + why + " — falling back to defaults; "
                + "the file was left untouched so you can fix it and run "
                + "/" + set.id() + " config reload";
        AnimaMod.LOGGER.error("Autarkia config: {} ({})", message, path);
        store.install(set.defaults());
        return List.of(message);
    }

    private static JsonElement lookup(JsonObject root, KnobSpec knob) {
        JsonElement section = root.get(knob.section());
        if (section == null || !section.isJsonObject()) {
            return null;
        }
        return section.getAsJsonObject().get(knob.leaf());
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
        Set<String> known = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        for (KnobSpec knob : set.knobs()) {
            known.add(knob.key());
            sections.add(knob.section());
        }
        List<String> problems = new ArrayList<>();
        for (String sectionName : root.keySet()) {
            if (sectionName.equals(DANGER_SECTION)) {
                continue; // open-key section — its own loader validates values, never keys
            }
            JsonElement section = root.get(sectionName);
            if (!sections.contains(sectionName)) {
                problems.add(String.format(Locale.ROOT, "unknown section \"%s\" — ignored", sectionName));
                continue;
            }
            if (!section.isJsonObject()) {
                problems.add(String.format(Locale.ROOT,
                        "\"%s\" should be an object — ignored", sectionName));
                continue;
            }
            for (String leaf : section.getAsJsonObject().keySet()) {
                if (leaf.startsWith(DOC_PREFIX.trim())) {
                    continue; 
                }
                if (!known.contains(sectionName + "." + leaf)) {
                    problems.add(String.format(Locale.ROOT,
                            "unknown key \"%s.%s\" — ignored", sectionName, leaf));
                }
            }
        }
        return problems;
    }
}
