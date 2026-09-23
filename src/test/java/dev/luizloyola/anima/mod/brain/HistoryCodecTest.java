package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.history.Doings;
import dev.luizloyola.anima.core.brain.history.History;
import dev.luizloyola.anima.core.brain.history.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A history survives a save, and one entry this install cannot read costs only that entry. */
class HistoryCodecTest {

    private static final List<History.Entry> SAVED = List.of(
            new History.Entry(Deed.of(Doings.FLEEING, Slot.entity("zombie")), 900, 3),
            new History.Entry(Deed.of(Doings.EATING), 400, 1));

    @Test
    void aHistoryRoundTrips() {
        JsonElement encoded = BrainState.HISTORY.encodeStart(JsonOps.INSTANCE, SAVED).getOrThrow();
        assertEquals(SAVED, BrainState.HISTORY.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void anEntryWhoseDoingIsGoneIsDroppedAndTheRestLoad() {
        JsonArray encoded = BrainState.HISTORY.encodeStart(JsonOps.INSTANCE, SAVED).getOrThrow()
                .getAsJsonArray();
        JsonObject orphan = encoded.get(0).getAsJsonObject().deepCopy();
        orphan.addProperty("doing", "a_doing_a_removed_mod_declared");
        encoded.add(orphan);
        JsonObject misshapen = encoded.get(1).getAsJsonObject().deepCopy();
        misshapen.add("slots", new JsonArray()); // eating takes none; give fleeing none instead
        misshapen.addProperty("doing", Doings.FLEEING.key());
        encoded.add(misshapen);

        assertEquals(SAVED, BrainState.HISTORY.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
}
