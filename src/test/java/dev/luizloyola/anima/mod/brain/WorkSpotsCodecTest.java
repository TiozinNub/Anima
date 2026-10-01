package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.history.WorkSpots;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where a body worked survives a restart, so a sweep after it still finds its own drops. */
class WorkSpotsCodecTest {

    @Test
    void theSpotsComeBackInOrder() {
        List<WorkSpots.Spot> saved = List.of(new WorkSpots.Spot(new Pos(3, -60, 7), 1200),
                new WorkSpots.Spot(new Pos(-4, 70, 0), 900));
        var encoded = BrainState.WORK_SPOTS.encodeStart(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(saved, BrainState.WORK_SPOTS.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
}
