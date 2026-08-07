package dev.luizloyola.anima.core.config;

/**
 * Anima's own live configuration — the store behind every {@link Knob}.
 *
 * <p>A thin, statically-reachable face on a {@link ConfigStore}: Anima's tunables are read on hot
 * paths and {@code Config.get().i(Knob.X)} is the shape those call sites want. A consuming mod
 * holds its own — see {@link KnobSet}.
 *
 * <p><b>Tests</b> start at the documented defaults; one that installs should {@link #reset} after.
 */
public final class Config {

    /** Anima's knob set: {@code config/anima.toml}, edited with {@code /anima config}. */
    public static final KnobSet SET = KnobSet.of("anima", "Anima", Knob.values());

    private static final ConfigStore STORE = new ConfigStore(SET);

    private Config() {
    }

    /** The store itself, for the file and command layers. */
    public static ConfigStore store() {
        return STORE;
    }

    /** The configuration in force right now. Never null. */
    public static ConfigValues get() {
        return STORE.get();
    }

    /** Swaps in a new configuration; every subsequent read sees it whole. */
    public static void install(ConfigValues config) {
        STORE.install(config);
    }

    /** Back to the documented defaults. */
    public static void reset() {
        STORE.reset();
    }
}
