package dev.luizloyola.anima.mod.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Waypoint;
import dev.luizloyola.anima.mod.nav.Doorways;
import dev.luizloyola.anima.mod.nav.Navigator;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A walk up a ladder, through a door it has yet to shut, survives a save. */
class WalkCodecTest {

    private static final Navigator.Walk WALK = new Navigator.Walk("FOLLOWING",
            new BlockPos(4, 70, 2),
            List.of(new Waypoint(3, 65, 2, MoveType.CLIMB), new Waypoint(4, 70, 2, MoveType.CLIMB)),
            true, 0, "WALK", 0, 0, 0, -1, 3, 0, 0, "NONE",
            List.of(new Doorways.Passed(new BlockPos(1, 64, 2), true)));

    @Test
    void aWalkRoundTripsWithItsDoorsAndItsClimb() {
        JsonElement encoded = BrainState.WALK.encodeStart(JsonOps.INSTANCE, WALK).getOrThrow();
        assertEquals(WALK, BrainState.WALK.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void aWalkSavedBeforeDoorsLoadsWithNoneToShut() {
        JsonObject encoded = BrainState.WALK.encodeStart(JsonOps.INSTANCE, WALK).getOrThrow()
                .getAsJsonObject();
        encoded.remove("doors");
        assertEquals(List.of(), BrainState.WALK.parse(JsonOps.INSTANCE, encoded).getOrThrow().doors());
    }
}
