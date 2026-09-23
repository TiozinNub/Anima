package dev.luizloyola.anima.core.social.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Slot;
import dev.luizloyola.anima.core.brain.history.When;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A deed said out loud comes back as the same deed, told the same distance ago. */
class RecountingTest {

    private static final Deed FLED = Deed.of(Doings.FLEEING, Slot.entity("zombie"));

    @Test
    void aToldDeedReadsBack() {
        Map<String, String> payload = Recounting.payload(new History.Entry(FLED, 1_000, 2), 3_000);

        assertEquals(Optional.of(new Recounting.Told(FLED, When.JUST_NOW)), Recounting.read(payload));
    }

    @Test
    void whenIsFixedAsTheLineIsSaid() {
        Map<String, String> payload = Recounting.payload(new History.Entry(FLED, 0, 1), 30_000);

        assertEquals(When.YESTERDAY, Recounting.read(payload).orElseThrow().when(),
                "the payload carries the bucket, so re-reading it later cannot move it");
    }

    @Test
    void aLineThatTellsNoDeedReadsAsNone() {
        assertEquals(Optional.empty(), Recounting.read(Map.of("topic", "weather")));
    }

    @Test
    void aDeedThisInstallCannotReadIsNone() {
        Map<String, String> payload =
                new HashMap<>(Recounting.payload(new History.Entry(FLED, 0, 1), 0));
        payload.put(Recounting.DID, "a_doing_nobody_declared");
        assertEquals(Optional.empty(), Recounting.read(payload));

        Map<String, String> short1 = new HashMap<>(Recounting.payload(new History.Entry(FLED, 0, 1), 0));
        short1.remove("slot1");
        assertEquals(Optional.empty(), Recounting.read(short1), "a slot missing is not a guess");
    }
}
