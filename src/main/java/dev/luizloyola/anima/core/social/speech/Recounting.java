package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doing;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.When;
import java.util.ArrayList;
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
    /** {@code slot1}, {@code slot2}… — one per declared slot, encoded by {@link Slot#encode}. */
    private static final String SLOT = "slot";

    /** A deed as a line tells it: what, and how long ago. */
    public record Told(Deed deed, When when) {
    }

    private Recounting() {
    }

    /** The payload that tells {@code entry} at {@code now}. */
    public static Map<String, String> payload(History.Entry entry, long now) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DID, entry.deed().doing().key());
        List<Slot> slots = entry.deed().slots();
        for (int i = 0; i < slots.size(); i++) {
            out.put(SLOT + (i + 1), slots.get(i).encode());
        }
        out.put(WHEN, When.of(now - entry.lastTick()).name());
        return out;
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
        List<Slot> slots = new ArrayList<>(doing.slots().size());
        for (int i = 1; i <= doing.slots().size(); i++) {
            String encoded = payload.get(SLOT + i);
            Optional<Slot> slot = encoded == null ? Optional.empty() : Slot.decode(encoded);
            if (slot.isEmpty()) {
                return Optional.empty();
            }
            slots.add(slot.get());
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
