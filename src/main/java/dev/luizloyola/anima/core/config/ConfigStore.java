package dev.luizloyola.anima.core.config;

/**
 * The live configuration for one {@link KnobSet} — an atomically-swapped {@link ConfigValues} that
 * every tunable in that set is read through.
 *
 * <p>Safe because what is shared is <b>immutable</b>: {@link #install} swaps the reference, never
 * mutating a value, so a reload can land mid-tick without a reader seeing a half-applied
 * configuration. {@code volatile} because reads happen off the server thread too — pathfinding runs
 * on a worker.
 *
 * <p>A read is a volatile read plus an array index, so {@code config reload} takes effect everywhere
 * at once, not only on newly-constructed objects. Read on use; never cache into a field.
 *
 * <p>One store per set, so a consuming mod's reload never disturbs Anima's.
 */
public final class ConfigStore {

    private final KnobSet set;
    private volatile ConfigValues current;

    public ConfigStore(KnobSet set) {
        this.set = set;
        this.current = set.defaults();
    }

    public KnobSet set() {
        return set;
    }

    /** The configuration in force right now. Never null. */
    public ConfigValues get() {
        return current;
    }

    /** Swaps in a new configuration; every subsequent read sees it whole. */
    public void install(ConfigValues config) {
        this.current = config == null ? set.defaults() : config;
    }

    /** Back to the documented defaults — teardown for tests, and the "lost the file" path. */
    public void reset() {
        this.current = set.defaults();
    }
}
