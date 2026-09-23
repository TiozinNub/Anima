package dev.luizloyola.anima.core.brain.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a body did lately — the most recent DISTINCT deeds, newest first, for small talk to draw
 * from. The arbiter writes one when a grant ends in success (see the social history spec,
 * 2026-09-23-small-talk-history-design.md).
 *
 * <p><b>Repeats merge.</b> A deed equal in doing and slots to one already held moves to the front
 * with its time and count updated: twenty log trips for the same yard are one entry, and eight
 * entries still hold eight different things to talk about.
 */
public final class History {

    public static final int CAPACITY = 8;
    /** Three in-game days: older than this is not "lately" any more. */
    public static final long MAX_AGE_TICKS = 72_000;

    /** One deed, when it last happened, and how many times it has happened while held. */
    public record Entry(Deed deed, long lastTick, int times) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Writes {@code deed} as done at {@code now}. A doing that is not remembered is dropped here. */
    public void record(Deed deed, long now) {
        if (!deed.doing().remembered()) {
            return;
        }
        int times = 1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).deed().equals(deed)) {
                times += entries.remove(i).times();
                break;
            }
        }
        entries.add(0, new Entry(deed, now, times));
        prune(now);
    }

    /** Everything still recent at {@code now}, newest first. */
    public List<Entry> recent(long now) {
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (now - entry.lastTick() <= MAX_AGE_TICKS) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The saved form, newest first. */
    public List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** The load path. Order is trusted; the cap is re-applied, the age waits for the next read. */
    public void restore(List<Entry> saved) {
        entries.clear();
        entries.addAll(saved);
        while (entries.size() > CAPACITY) {
            entries.remove(entries.size() - 1);
        }
    }

    private void prune(long now) {
        entries.removeIf(entry -> now - entry.lastTick() > MAX_AGE_TICKS);
        while (entries.size() > CAPACITY) {
            entries.remove(entries.size() - 1);
        }
    }
}
