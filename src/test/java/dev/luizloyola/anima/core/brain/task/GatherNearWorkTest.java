package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import org.junit.jupiter.api.Test;

/** A sweep of a body's own drops takes what its work let fall and leaves the rest. */
class GatherNearWorkTest {

    private static Drop drop(int x, int z) {
        Pos at = new Pos(x, 64, z);
        return new Drop(at, "minecraft:oak_sapling", Region.of(at));
    }

    @Test
    void onlyDropsNearOwnWorkAreWanted() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.time = 500;
        ctx.workSpots.record(new Pos(0, 64, 0), 400);
        assertTrue(GatherNearbyDrops.wanted(drop(3, 2), ItemSpec.ANYTHING, true, ctx));
        assertFalse(GatherNearbyDrops.wanted(drop(12, 0), ItemSpec.ANYTHING, true, ctx));
        assertTrue(GatherNearbyDrops.wanted(drop(12, 0), ItemSpec.ANYTHING, false, ctx));
    }

    @Test
    void withNoWorkLatelyNothingIsItsOwn() {
        FakeContext ctx = new FakeContext();
        assertFalse(GatherNearbyDrops.wanted(drop(0, 0), ItemSpec.ANYTHING, true, ctx));
    }
}
