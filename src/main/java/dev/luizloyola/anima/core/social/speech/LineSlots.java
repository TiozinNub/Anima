package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.brain.history.Slot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The arguments a line carries in its payload — {@code slot1}, {@code slot2}… each a
 * {@link Slot} encoded — rendered in order as the line's {@code %1$s}, {@code %2$s}…. A told deed
 * uses them, and so does a small-talk topic that names something ("a fine Iron Axe").
 */
public final class LineSlots {

    private static final String KEY = "slot";

    private LineSlots() {
    }

    /** {@code payload} with {@code slots} added after whatever it already carries. */
    public static Map<String, String> with(Map<String, String> payload, List<Slot> slots) {
        Map<String, String> out = new LinkedHashMap<>(payload);
        for (int i = 0; i < slots.size(); i++) {
            out.put(KEY + (i + 1), slots.get(i).encode());
        }
        return out;
    }

    /**
     * The slots {@code payload} carries, in order — up to the first one missing. Empty when one
     * cannot be read: a line whose arguments are half there says nothing sensible.
     */
    public static Optional<List<Slot>> of(Map<String, String> payload) {
        List<Slot> out = new ArrayList<>();
        for (int i = 1; payload.containsKey(KEY + i); i++) {
            Optional<Slot> slot = Slot.decode(payload.get(KEY + i));
            if (slot.isEmpty()) {
                return Optional.empty();
            }
            out.add(slot.get());
        }
        return Optional.of(out);
    }
}
