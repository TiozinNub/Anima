package dev.luizloyola.anima.core.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One mod's complete set of tunables — the unit the whole config stack operates on. A set owns a
 * file, a command and a GUI page, and a consuming mod declares its own rather than reaching for
 * Anima's, because a library behind another mod's command is not adoptable and inherited knobs hide
 * which are whose.
 *
 * <pre>{@code
 * public static final KnobSet SET = KnobSet.of("mymod", "My Mod", MyKnob.values());
 * }</pre>
 *
 * <p><b>The set assigns the storage index, not the knob:</b> declaration order is the index and
 * {@link ConfigValues} asks the set, so two enums in one set (both counting from zero) cannot
 * write over each other.
 */
public final class KnobSet {

    private final String id;
    private final String title;
    private final List<KnobSpec> knobs;
    /** Every knob's slot in a value array, assigned here so no knob has to know its own. */
    private final Map<KnobSpec, Integer> indices;
    /** The same knobs by dotted key — what a file line, a command argument and the GUI arrive as. */
    private final Map<String, KnobSpec> byKey;
    /** Built once, immutable and shared: callers compare against it by identity as well as value. */
    private final ConfigValues defaults;

    private KnobSet(String id, String title, List<KnobSpec> knobs) {
        this.id = id;
        this.title = title;
        this.knobs = List.copyOf(knobs);

        Map<KnobSpec, Integer> indices = new HashMap<>();
        Map<String, KnobSpec> byKey = new HashMap<>();
        for (KnobSpec knob : this.knobs) {
            if (byKey.put(knob.key(), knob) != null) {
                throw new IllegalArgumentException("duplicate knob key in " + id + ": " + knob.key());
            }
            indices.put(knob, indices.size());
        }
        this.indices = Map.copyOf(indices);
        this.byKey = Map.copyOf(byKey);

        this.defaults = ConfigValues.defaults(this);
    }

    /** Declares a set from an enum's {@code values()}. */
    public static KnobSet of(String id, String title, KnobSpec[] knobs) {
        return new KnobSet(id, title, List.of(knobs));
    }

    /**
     * Declares a set from several sources — a mod's own enum beside generated knob families. Order
     * across the parts is the declaration order; only this set knows where they landed.
     */
    public static KnobSet of(String id, String title, List<? extends KnobSpec> knobs) {
        return new KnobSet(id, title, List.copyOf(knobs));
    }

    /**
     * Where {@code knob}'s value lives in a value array of this set.
     *
     * @throws IllegalArgumentException if the knob belongs to some other set — which would
     *     otherwise read or write a neighbouring knob's value and be very hard to see.
     */
    public int indexOf(KnobSpec knob) {
        Integer index = indices.get(knob);
        if (index == null) {
            throw new IllegalArgumentException(knob.key() + " is not a knob of " + this);
        }
        return index;
    }

    /** The mod id — the config file's base name and the command root. */
    public String id() {
        return id;
    }

    /** The operator-facing name, for command output and the GUI title. */
    public String title() {
        return title;
    }

    /** Every knob in this set, in declaration order. */
    public List<KnobSpec> knobs() {
        return knobs;
    }

    /** The knob with this dotted key, or empty — the lookup behind {@code config get}/{@code set}. */
    public Optional<KnobSpec> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** How many knobs this set holds — the size of a value array. */
    public int size() {
        return knobs.size();
    }

    /** Every knob at its documented default; also what an absent file means. */
    public ConfigValues defaults() {
        return defaults;
    }

    /** The file this set persists to, relative to the config directory. */
    public String fileName() {
        return id + ".json";
    }

    /** Translation key root for the optional GUI — {@code <id>.config.category.<section>} etc. */
    public String langRoot() {
        return id + ".config";
    }

    @Override
    public String toString() {
        return "KnobSet(" + id + ", " + knobs.size() + " knobs)";
    }
}
