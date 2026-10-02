package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A sweep walks each flock once the legs say no way leads there, not once a lap. */
class GatherNearbyDropsTest {

    private static Drop drop(int x, int z) {
        Pos at = new Pos(x, 64, z);
        return new Drop(at, "minecraft:stick", Region.of(at));
    }

    private static void fail(FakeContext ctx, MoveFailure why) {
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(why);
    }

    @Test
    void aFlockTheLegsFindNoWayToIsSearchedOnce() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.drops = List.of(drop(5, 0));
        GatherNearbyDrops sweep = new GatherNearbyDrops(ItemSpec.ANYTHING);
        assertEquals(TaskStatus.RUNNING, sweep.tick(ctx));
        fail(ctx, MoveFailure.STRANDED);
        assertEquals(TaskStatus.FAILED, sweep.tick(ctx), "nothing else lies in sight");
        assertEquals(1, ctx.mover.moveToCalls);
    }

    /** An obtain starts a fresh sweep each round: the body, not the sweep, must remember. */
    @Test
    void theNextSweepDoesNotWalkForADropTheLastFoundNoWayTo() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.drops = List.of(drop(5, 0));
        GatherNearbyDrops sweep = new GatherNearbyDrops(ItemSpec.ANYTHING);
        sweep.tick(ctx);
        fail(ctx, MoveFailure.STRANDED);
        sweep.tick(ctx);
        ctx.mover.setState(MoveState.IDLE);
        assertFalse(new PickUpNearby(ItemSpec.ANYTHING).applicable(ctx));
        assertEquals(TaskStatus.FAILED, new GatherNearbyDrops(ItemSpec.ANYTHING).tick(ctx));
        assertEquals(1, ctx.mover.moveToCalls);
    }

    @Test
    void theNextFlockIsStillWalkedTo() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.drops = List.of(drop(5, 0), drop(20, 0));
        GatherNearbyDrops sweep = new GatherNearbyDrops(ItemSpec.ANYTHING);
        sweep.tick(ctx);
        assertEquals(5, ctx.mover.lastX);
        fail(ctx, MoveFailure.UNREACHABLE);
        assertEquals(TaskStatus.RUNNING, sweep.tick(ctx));
        assertEquals(20, ctx.mover.lastX);
        assertEquals(List.of(new Pos(5, 64, 0)), sweep.struck());
    }

    @Test
    void aWalkThatWentWrongOnTheWayIsTriedAgain() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.drops = List.of(drop(5, 0));
        GatherNearbyDrops sweep = new GatherNearbyDrops(ItemSpec.ANYTHING);
        sweep.tick(ctx);
        fail(ctx, MoveFailure.STALLED);
        assertEquals(TaskStatus.RUNNING, sweep.tick(ctx));
        assertEquals(2, ctx.mover.moveToCalls);
    }
}
