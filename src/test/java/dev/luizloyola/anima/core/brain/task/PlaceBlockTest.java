package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

class PlaceBlockTest {

    private static final Placing PLANKS = Placing.of("minecraft:oak_planks", new Pos(1, 64, 1));

    @Test
    void theBlockGoesInOnceThePaceIsSpent() {
        FakeContext ctx = new FakeContext();
        int pace = ctx.profile().i(ProfileAspect.PLACE_COOLDOWN_TICKS);
        assertEquals(10, pace, "the test species places at the default pace");
        PlaceBlock place = new PlaceBlock(PLANKS);
        for (int tick = 0; tick < pace; tick++) {
            assertEquals(TaskStatus.RUNNING, place.tick(ctx), "tick " + tick);
            assertTrue(ctx.placer.placings.isEmpty(), "nothing goes in while it waits");
        }
        assertEquals(TaskStatus.SUCCESS, place.tick(ctx));
        assertEquals(1, ctx.placer.placings.size());
    }

    @Test
    void aPlaceRestoredMidWaitWaitsOnlyWhatIsLeft() {
        FakeContext ctx = new FakeContext();
        PlaceBlock place = new PlaceBlock(PLANKS, 9);
        assertEquals(TaskStatus.RUNNING, place.tick(ctx));
        assertEquals(TaskStatus.SUCCESS, place.tick(ctx));
    }

    @Test
    void aCellTheBodysBoxReachesIntoIsOccupied() {
        FakeContext ctx = new FakeContext();
        assertFalse(PlaceBlock.occupied(ctx, new Pos(0, 64, -1)), "a centred body keeps to its cell");
        ctx.percepts.footprint = new Region(new Pos(0, 64, -1), new Pos(0, 65, 0));
        assertTrue(PlaceBlock.occupied(ctx, new Pos(0, 64, -1)), "0.04 over the edge is enough to refuse");
        assertTrue(PlaceBlock.occupied(ctx, new Pos(0, 65, 0)), "the head's cell too");
    }

    @Test
    void aRefusalStillComesAfterThePace() {
        FakeContext ctx = new FakeContext();
        ctx.placer.refuse = true;
        PlaceBlock place = new PlaceBlock(PLANKS, 10);
        assertEquals(TaskStatus.FAILED, place.tick(ctx));
    }
}
