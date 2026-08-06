package dev.luizloyola.anima.core.appearance;

/**
 * When an eye is shut — as a function of time, holding no state at all.
 *
 * <h2>Why a function and not a timer</h2>
 * Nothing to allocate per body, nothing to tick (one call on the frames that ask), and nothing to
 * keep in step across a hot swap — a timer field added by a redefinition arrives zero, so every eye
 * on screen shuts at once.
 *
 * <h2>The numbers, and why they are these numbers</h2>
 * A blink is <b>150ms</b>; half a second reads as a wink or a doze. The interval is <b>scattered,
 * never periodic</b> — a metronome is the clearest tell of a machine, and a resting human blinks
 * 15–20 a minute without ever being regular. Roughly one blink in five is a <b>double</b>, which is
 * most of what makes the rhythm read as alive.
 *
 * <p>Shared with the appearance editor so the tool keeps predicting the game; its scheduler differs
 * in shape, but these numbers are the one definition.
 */
public final class Blink {
    private Blink() {}

    /** A blink closes and reopens in about this long — three ticks. */
    public static final int SHUT_MILLIS = 150;

    /** Resting blink rate is 15–20 a minute, so a blink lands roughly once per this window. */
    public static final int WINDOW_MILLIS = 3_600;

    /** Roughly one blink in five is a double. */
    public static final int DOUBLE_PERCENT = 18;

    public static final int DOUBLE_GAP_MILLIS = 200;

    /**
     * Keeps a blink away from its window's edges, so two blinks either side of a boundary cannot
     * land on top of each other. With this margin consecutive blinks are 1.1 to 6.1 seconds apart,
     * clustered in the middle.
     */
    private static final float EDGE_MARGIN = 0.15F;

    /**
     * Whether the eye seeded with {@code seed} is shut at {@code millis}.
     *
     * @param seed  what makes one agent's rhythm theirs — two bodies sharing a seed blink in unison
     */
    public static boolean shutAt(long seed, long millis) {
        long window = Math.floorDiv(millis, WINDOW_MILLIS);
        // The previous window is checked too: a blink placed late in it, or the second half of a
        // double, can still be shut after the boundary has passed.
        return shutInWindow(seed, window, millis) || shutInWindow(seed, window - 1, millis);
    }

    private static boolean shutInWindow(long seed, long window, long millis) {
        long start = window * WINDOW_MILLIS + offsetIn(seed, window);
        if (millis >= start && millis < start + SHUT_MILLIS) {
            return true;
        }
        if (percent(seed ^ 0x9E3779B97F4A7C15L, window) >= DOUBLE_PERCENT) {
            return false;
        }
        long second = start + SHUT_MILLIS + DOUBLE_GAP_MILLIS;
        return millis >= second && millis < second + SHUT_MILLIS;
    }

    /** Where in its window this blink falls, kept clear of both edges. */
    private static long offsetIn(long seed, long window) {
        int usable = (int) (WINDOW_MILLIS * (1 - 2 * EDGE_MARGIN)) - SHUT_MILLIS - DOUBLE_GAP_MILLIS;
        return (long) (WINDOW_MILLIS * EDGE_MARGIN) + percent(seed, window) * usable / 100;
    }

    /**
     * A stable 0–99 from a seed and a window.
     *
     * <p>SplitMix64's finalising mix: neighbouring windows are uncorrelated, so a rhythm never
     * drifts into a pattern.
     */
    private static int percent(long seed, long window) {
        long mixed = seed * 0x9E3779B97F4A7C15L + window * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (int) Math.floorMod(mixed, 100L);
    }
}
