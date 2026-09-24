package dev.luizloyola.anima.core.social.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.history.Slot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A line's arguments survive the payload, in order, beside whatever else it carries. */
class LineSlotsTest {

    private static final List<Slot> AXE_AND_YARD =
            List.of(Slot.item("minecraft:iron_axe"), Slot.lang("autarkia.purpose.yard"));

    @Test
    void slotsRoundTripBesideTheRest() {
        Map<String, String> payload = LineSlots.with(Map.of("topic", "them.holding"), AXE_AND_YARD);

        assertEquals("them.holding", payload.get("topic"));
        assertEquals(Optional.of(AXE_AND_YARD), LineSlots.of(payload));
    }

    @Test
    void aLineWithoutSlotsHasNone() {
        assertEquals(Optional.of(List.of()), LineSlots.of(Map.of("topic", "weather")));
    }

    @Test
    void anUnreadableSlotIsNoArgumentsAtAll() {
        Map<String, String> payload = new HashMap<>(LineSlots.with(Map.of(), AXE_AND_YARD));
        payload.put("slot2", "nonsense");

        assertEquals(Optional.empty(), LineSlots.of(payload),
                "half the arguments would put the wrong word in the wrong place");
    }
}
