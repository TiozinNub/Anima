package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.history.Unreached;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The stands a body's walks found no way to survive a restart, saved with its plan. */
class UnreachedCodecTest {

    private static final List<Unreached.Strike> STRUCK = List.of(
            new Unreached.Strike(new Pos(61, 69, -732), 787_548L),
            new Unreached.Strike(new Pos(62, 69, -732), 787_550L));

    @Test
    void theStrikesComeBackInOrder() {
        var encoded = BrainState.UNREACHED.encodeStart(JsonOps.INSTANCE, STRUCK).getOrThrow();
        assertEquals(STRUCK, BrainState.UNREACHED.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void theyTravelInTheSavedPlan() {
        BrainDriver.BrainSnapshot saved = new BrainDriver.BrainSnapshot(
                new TaskExecutor.State(null, List.of(), null, null, null),
                new Arbiter.Grant("", false, ""), STRUCK);

        var encoded = BrainState.brain().encodeStart(JsonOps.INSTANCE, saved).getOrThrow();

        assertEquals(STRUCK, BrainState.brain().parse(JsonOps.INSTANCE, encoded).getOrThrow()
                .unreached());
    }
}
