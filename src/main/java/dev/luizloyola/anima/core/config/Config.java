package dev.luizloyola.anima.core.config;

/**
 * The live configuration — one process-wide, atomically-swapped {@link ConfigValues} that every
 * tunable reads through. A global rather than a parameter threaded through every instinct, task and
 * sensor, and safe because what is shared is <b>immutable</b>: {@link #install} swaps the reference
 * and never mutates, so a reload lands mid-tick with no reader seeing a half-applied configuration.
 * {@code volatile}, because reads happen off the server thread — pathfinding runs on a worker.
 *
 * <p>Reads are cheap enough for per-tick use (a volatile read plus an array index). That is what
 * lets {@code /autarkia config reload} take effect everywhere at once rather than only on
 * newly-constructed objects. Read on use; do not cache into a field.
 *
 * <p><b>Tests</b> start at {@link ConfigValues#DEFAULTS} and should {@link #reset} after installing
 * anything.
 */
public final class Config {

    private static volatile ConfigValues current = ConfigValues.DEFAULTS;

    private Config() {
    }

    /** The configuration in force right now. Never null. */
    public static ConfigValues get() {
        return current;
    }

    /** Swaps in a new configuration; every subsequent read sees it whole. */
    public static void install(ConfigValues config) {
        current = config == null ? ConfigValues.DEFAULTS : config;
    }

    /** Back to {@link ConfigValues#DEFAULTS} — teardown for tests, and the "lost the file" path. */
    public static void reset() {
        current = ConfigValues.DEFAULTS;
    }
}
