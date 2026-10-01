package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A table put down to craft on is still the body's to pick up after a restart. */
class FieldTablesCodecTest {

    @Test
    void theTablesComeBackInOrder() {
        List<Pos> saved = List.of(new Pos(3, -60, 7), new Pos(-4, 70, 0));
        var encoded = BrainState.FIELD_TABLES.encodeStart(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(saved, BrainState.FIELD_TABLES.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
}
