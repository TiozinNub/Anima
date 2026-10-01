package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlaceFromTest {

    private static final Placing PLANKS = Placing.of("minecraft:oak_planks", new Pos(8, 64, 0));
    private static final Pos PLANNED = new Pos(5, 64, 0);

    private static Being builder(Pos at) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.AGENT, "person", "Bia", null, at, 5,
                Being.HUMANOID_EYE_HEIGHT, false, 1, 0, false, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    private static GoTo walk(FakeContext ctx) {
        PlaceFrom place = new PlaceFrom(PLANKS, List.of(), PLANNED, false);
        return assertInstanceOf(GoTo.class, place.methods().get(0).decompose(ctx).get(0));
    }

    @Test
    void theBodyWalksToThePlannedStand() {
        FakeContext ctx = new FakeContext();
        GoTo walk = walk(ctx);
        assertEquals(PLANNED, new Pos(walk.x(), walk.y(), walk.z()));
    }

    @Test
    void aStandAnotherBodyHoldsIsLeftToIt() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.beings = List.of(builder(PLANNED));
        GoTo walk = walk(ctx);
        assertNotEquals(PLANNED, new Pos(walk.x(), walk.y(), walk.z()), "the other builder stands there");
    }
}
