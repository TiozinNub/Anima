package dev.luizloyola.anima.core.config;

import java.util.List;
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
 */
public final class KnobSet {

    private final String id;
    private final String title;
    private final List<KnobSpec> knobs;
    /** Built once, immutable and shared: callers compare against it by identity as well as value. */
    private final ConfigValues defaults;

    private KnobSet(String id, String title, List<KnobSpec> knobs) {
        this.id = id;
        this.title = title;
        this.knobs = List.copyOf(knobs);
        this.defaults = ConfigValues.defaults(this);
    }

    /** Declares a set from an enum's {@code values()}. */
    public static KnobSet of(String id, String title, KnobSpec[] knobs) {
        return new KnobSet(id, title, List.of(knobs));
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
        for (KnobSpec knob : knobs) {
            if (knob.key().equals(key)) {
                return Optional.of(knob);
            }
        }
        return Optional.empty();
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
