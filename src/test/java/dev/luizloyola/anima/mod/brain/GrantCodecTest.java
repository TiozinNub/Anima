package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A claim set aside by a drive is held for a night from when it was set aside, across a restart. */
class GrantCodecTest {

    @Test
    void whenTheClaimWasSetAsideTravelsInTheSavedPlan() {
        Arbiter.Grant grant = new Arbiter.Grant("shelter", false, "shelter", 12_414L);
        BrainDriver.BrainSnapshot saved = new BrainDriver.BrainSnapshot(
                new TaskExecutor.State(null, List.of(), null, null, null), grant, List.of());

        var encoded = BrainState.brain().encodeStart(JsonOps.INSTANCE, saved).getOrThrow();

        assertEquals(grant, BrainState.brain().parse(JsonOps.INSTANCE, encoded).getOrThrow().grant());
    }
}
