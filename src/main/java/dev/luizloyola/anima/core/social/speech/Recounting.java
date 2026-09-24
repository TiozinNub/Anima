package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doing;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.When;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A history entry said out loud — the payload keys a {@link Deed} rides under in a line, written by
 * a consumer's small talk and read back by whatever renders the line. A line carrying one renders
 * from the doing's own lang key rather than its act's.
 *
 * <p><b>{@code when} is fixed as the line is said</b>, not re-derived as it is drawn: a panel
 * re-rendering an old line must not move it from "just now" to "earlier".
 */
public final class Recounting {

    public static final String DID = "did";
    public static final String WHEN = "when";

    /** A deed as a line tells it: what, and how long ago. */
    public record Told(Deed deed, When when) {
    }

    private Recounting() {
    }

    /** The payload that tells {@code entry} at {@code now}. */
    public static Map<String, String> payload(History.Entry entry, long now) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DID, entry.deed().doing().key());
        out.put(WHEN, When.of(now - entry.lastTick()).name());
        return LineSlots.with(out, entry.deed().slots());
    }

    /**
     * The deed {@code payload} tells, or empty for a line that tells none — or one whose doing or
     * slots this install cannot read, which then renders as whatever its act says.
     */
    public static Optional<Told> read(Map<String, String> payload) {
        String key = payload.get(DID);
        Doing doing = key == null ? null : Doings.byKey(key).orElse(null);
        if (doing == null) {
            return Optional.empty();
        }
        List<Slot> slots = LineSlots.of(payload).orElse(List.of());
        if (slots.size() != doing.slots().size()) {
            return Optional.empty();
        }
        return Optional.of(new Told(new Deed(doing, slots), when(payload.get(WHEN))));
    }

    private static When when(String name) {
        for (When when : When.values()) {
            if (when.name().equals(name)) {
                return when;
            }
        }
        return When.EARLIER;
    }
}
