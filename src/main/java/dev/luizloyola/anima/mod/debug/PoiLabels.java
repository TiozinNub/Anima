package dev.luizloyola.anima.mod.debug;

import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import java.util.Locale;

/**
 * Saying what a remembered place is, briefly.
 *
 * <p><b>Short.</b> A label floats beside the thing it names, so the position and the
 * distance are already on screen and repeating them would make the text overlap its neighbours.
 * What is left is the three things a box cannot say: what kind of thing it is, which one of them,
 * and how long ago. The chat listing keeps the long form, where the position is the information.
 */
public final class PoiLabels {

    private PoiLabels() {
    }

    /**
     * A whole label, e.g. {@code "TREE 4 logs · 32s"} or {@code "DANGER creeper · 2m"}.
     *
     * @param now the observing world's game time, for the age
     */
    public static String of(PoiMemory memory, long now) {
        StringBuilder label = new StringBuilder(memory.kind().key().toUpperCase(Locale.ROOT));
        if (!memory.detail().isEmpty()) {
            label.append(' ').append(memory.detail());
        }
        // Units only where they mean something. "1 cells" beside a danger marker is noise; the
        // count of logs in a grove or head in a herd is the whole reason to look at it.
        if (!memory.kind().unit().isEmpty()) {
            label.append(' ').append(memory.units()).append(memory.kind().unit());
            if (memory.partial()) {
                label.append('+'); // at LEAST this much — the scan hit its cap
            }
        }
        return label.append(" · ").append(when(memory, now)).toString();
    }

    /**
     * The clock on a memory, phrased the way that memory works.
     *
     * <p>A kind that expires counts down — {@code "3m left"} — rather than making the reader
     * subtract against a fade window they have to remember. A kind with no deadline stays elapsed.
     */
    public static String when(PoiMemory memory, long now) {
        int lifetime = memory.kind().lifetimeTicks();
        if (lifetime <= 0) {
            return age(memory, now); // no deadline to count toward — a tree is right until felled
        }
        long remaining = lifetime - memory.age(now);
        return remaining <= 0 ? "gone" : brief(remaining) + " left";
    }

    /**
     * How long ago, for a kind that has no deadline: {@code "now"}, {@code "32s"}, {@code "2m"}.
     * Shared with the chat listing so the two cannot disagree about what "just now" means.
     */
    public static String age(PoiMemory memory, long now) {
        long seconds = memory.age(now) / 20;
        return seconds < 2 ? "now" : brief(seconds * 20);
    }

    /**
     * Ticks as the shortest thing worth reading: {@code "32s"}, {@code "2m"}. Public for the gist
     * tier, which ages the same way but has no {@link PoiMemory} to be asked about.
     */
    public static String ticks(long ticks) {
        return brief(ticks);
    }

    /** Ticks as the shortest thing worth reading: {@code "32s"}, {@code "2m"}. */
    private static String brief(long ticks) {
        long seconds = ticks / 20;
        return seconds < 120 ? seconds + "s" : (seconds / 60) + "m";
    }
}
