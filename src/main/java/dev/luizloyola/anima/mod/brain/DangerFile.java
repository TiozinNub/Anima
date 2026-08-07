package dev.luizloyola.anima.mod.brain;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import dev.luizloyola.anima.core.brain.sense.DangerStore;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.config.TomlDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * One species' flee weights on disk — {@code config/<mod id>-danger.toml}.
 *
 * <p><b>Its own artifact, not a config section:</b> hundreds of machine-written entries do not
 * belong beside thirty hand-tuned numbers, and it cannot be written at mod init — modded and
 * datapack entity types exist only once the registries freeze, so the generator runs at server
 * start.
 *
 * <p><b>Two halves, owned by different people.</b> {@code derived} is regenerated wholesale on every
 * load, so a new mod's creatures appear, an uninstalled one's stop lingering, and an operator's
 * edit there is lost (the file says so); {@code overrides} the generator never touches.
 *
 * <p><b>The guess is coarse on purpose:</b> {@link MobCategory} is free where the runtime
 * classification needs an instance. The overrides carry what it gets wrong — a zombified piglin is
 * {@code MONSTER} and behaviourally neutral.
 *
 * <p>Nothing here throws at the caller; a malformed file is reported and skipped.
 */
public final class DangerFile {

    private static final String DERIVED = "derived";
    private static final String OVERRIDES = "overrides";

    /** Weights clamp to this band; negative danger does not exist and 8 is already terror. */
    private static final double MIN = 0.0;
    private static final double MAX = 8.0;

    /**
     * What the generator guesses for a {@link MobCategory#MONSTER}. Everything else guesses 0 —
     * wrong only for the handful (wolf, polar bear, bee, iron golem, llama) the overrides name.
     */
    private static final double MONSTER_GUESS = 1.2;

    private final String modId;
    private final DangerStore store;

    public DangerFile(String modId, DangerStore store) {
        this.modId = modId;
        this.store = store;
    }

    /** {@code <game dir>/config/<mod id>-danger.toml}. */
    public Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(modId + "-danger.toml");
    }

    /**
     * Regenerates the derived half from the live registry, reads the overrides half back, installs
     * the result and writes the file. Call once per server start, after the registries freeze.
     */
    public void generate() {
        Map<String, Double> derived = fromRegistry();
        Map<String, Double> overrides = readOverrides();
        DangerTable table = store.get().withDerived(derived).withOverrides(overrides);
        store.install(table);
        save(table);
        AnimaMod.LOGGER.info("{} danger: {} entries derived from the registry, {} overridden",
                modId, derived.size(), overrides.size());
        warnOnOrphans(derived, overrides);
    }

    /**
     * Every entity type the registry knows, weighted by category. Keys are the species strings
     * {@code Being.species} carries (a bare path for vanilla, namespace-qualified otherwise), so
     * the file reads the way an operator would type it. Harmless types are included: an
     * {@code EntityType} cannot be asked whether it is living without building an instance.
     */
    private static Map<String, Double> fromRegistry() {
        Map<String, Double> derived = new TreeMap<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) {
                continue;
            }
            // MISC is not skipped, however tempting: it is mostly arrows and boats, but it is
            // also where the iron golem lives, and filtering it out dropped a genuine threat from
            // the table and left the override for it looking like an orphan.
            String species = id.getNamespace().equals("minecraft")
                    ? id.getPath()
                    : id.toString();
            derived.put(species, type.getCategory() == MobCategory.MONSTER ? MONSTER_GUESS : 0.0);
        }
        return derived;
    }

    /**
     * Says once when an override names something no longer in the registry — an entry left behind
     * by an uninstalled mod. Reported rather than pruned: it is somebody's tuning, and the mod may
     * come back.
     */
    private void warnOnOrphans(Map<String, Double> derived, Map<String, Double> overrides) {
        for (String species : overrides.keySet()) {
            if (species.equals(DangerTable.DEFAULT_KEY)
                    || species.equals(DangerTable.HOSTILE_KEY)
                    || derived.containsKey(species)) {
                continue;
            }
            AnimaMod.LOGGER.info("{} danger: override \"{}\" names nothing in the registry — "
                    + "left alone in case the mod that had it comes back", modId, species);
        }
    }

    /** The overrides half of the file, or the ones the mod declared when there is no file yet. */
    private Map<String, Double> readOverrides() {
        Path path = path();
        if (!Files.exists(path)) {
            return store.get().overrides(); // first run: whatever the mod author declared
        }
        CommentedConfig root;
        try {
            root = TomlDocument.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            AnimaMod.LOGGER.error("{} danger: could not read {} ({}) — using the declared weights "
                    + "and leaving your file alone", modId, path, TomlDocument.problem(e));
            return store.get().overrides();
        }
        // The LIST form of get(), not the dotted String form: an override key is an entity id,
        // written quoted and read back whole. "overrides.somemod:thing" as a dotted string would
        // break the first time an id carried a dot.
        Object section = root.get(List.of(OVERRIDES));
        if (!(section instanceof UnmodifiableConfig table)) {
            return Map.of();
        }
        Map<String, Double> overrides = new LinkedHashMap<>();
        for (UnmodifiableConfig.Entry entry : table.entrySet()) {
            String species = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof Number number) || value instanceof Boolean
                    || !Double.isFinite(number.doubleValue())) {
                AnimaMod.LOGGER.warn("{} danger: {}.{} should be a number, found {} — ignored",
                        modId, OVERRIDES, species, value);
                continue;
            }
            double raw = number.doubleValue();
            double clamped = Math.max(MIN, Math.min(MAX, raw));
            if (clamped != raw) {
                AnimaMod.LOGGER.warn(String.format(Locale.ROOT,
                        "%s danger: %s.%s is %s, outside [%s, %s] — using %s",
                        modId, OVERRIDES, species, raw, MIN, MAX, clamped));
            }
            overrides.put(species, clamped);
        }
        return overrides;
    }

    /** Writes both halves out, atomically. */
    public boolean save(DangerTable table) {
        Path path = path();
        try {
            TomlDocument.save(path, render(table));
            return true;
        } catch (IOException e) {
            AnimaMod.LOGGER.error("{} danger: could not write {}", modId, path, e);
            return false;
        }
    }

    /**
     * The exact text {@link #save} writes — pulled out so a test can check it round-trips. Each
     * half is introduced by a comment on its own table, so EDITS HERE ARE OVERWRITTEN is a real
     * TOML comment rather than the first of several hundred string entries.
     */
    public String render(DangerTable table) {
        CommentedConfig root = TomlDocument.document();

        root.set(List.of(DERIVED), TomlDocument.document());
        root.setComment(List.of(DERIVED), TomlDocument.comment(
                "How frightening each kind of thing is to a " + modId + " agent. 0 is not "
                        + "frightening at all; 1 is a zombie-grade nuisance. Anything not named "
                        + "here falls back to \"" + DangerTable.DEFAULT_KEY + "\".\n\n"
                        + "GENERATED from the entity registry every time the server starts, "
                        + "guessed from each type's category. EDITS HERE ARE OVERWRITTEN — put "
                        + "yours in \"" + OVERRIDES + "\" below, which is never touched."));
        table.derived().forEach((species, weight) -> root.set(List.of(DERIVED, species), weight));

        root.set(List.of(OVERRIDES), TomlDocument.document());
        root.setComment(List.of(OVERRIDES), TomlDocument.comment(
                "Yours. Anything here wins over the generated guess above and survives every "
                        + "regeneration. \"" + DangerTable.DEFAULT_KEY + "\" is what an unlisted "
                        + "species is worth; \"" + DangerTable.HOSTILE_KEY + "\" is what "
                        + "something that has attacked from cover is worth before it is "
                        + "identified."));
        table.overrides().forEach((species, weight) -> root.set(List.of(OVERRIDES, species), weight));

        return TomlDocument.render(root);
    }
}
