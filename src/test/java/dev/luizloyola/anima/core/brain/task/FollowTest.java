package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Following stays put while the one followed is near, sets off once it is far, aims beside it
 * rather than onto it, walks to the meeting place when it is out of sight, and fails there alone;
 * waiting for company ends when they are all near, or when the time is up.
 */
class FollowTest {

    private final BeingId leader = BeingId.of(UUID.randomUUID());

    private void leaderAt(FakeContext ctx, int x, int z) {
        Pos me = ctx.percepts.position;
        ctx.percepts.beings = List.of(FakePercepts.personAt(leader, new Pos(x, 64, z),
                Math.hypot(x - me.x(), z - me.z()), "Scout"));
    }

    @Test
    void nearbyItStaysPut() {
        FakeContext ctx = new FakeContext();
        leaderAt(ctx, 5, 0);
        Follow follow = new Follow(leader, null);

        assertEquals(TaskStatus.RUNNING, follow.tick(ctx));
        assertEquals(0, ctx.mover.moveToCalls);
    }

    @Test
    void farItWalksToItsOwnSideOfTheLeader() {
        FakeContext ctx = new FakeContext();
        leaderAt(ctx, 20, 0);
        Follow follow = new Follow(leader, null);

        follow.tick(ctx);

        assertEquals(1, ctx.mover.moveToCalls);
        assertEquals(16, ctx.mover.lastX, "four short of the leader, on the follower's side");
        assertEquals(0, ctx.mover.lastZ);
    }

    @Test
    void aDriftingLeaderReAimsTheWalk() {
        FakeContext ctx = new FakeContext();
        leaderAt(ctx, 20, 0);
        Follow follow = new Follow(leader, null);
        follow.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        follow.tick(ctx);

        leaderAt(ctx, 20, 10);
        follow.tick(ctx);

        assertEquals(2, ctx.mover.moveToCalls);
    }

    @Test
    void outOfSightItGoesWhereTheyMeetAndFailsThereAlone() {
        FakeContext ctx = new FakeContext();
        Pos meet = new Pos(40, 64, 0);
        Follow follow = new Follow(leader, meet);

        assertEquals(TaskStatus.RUNNING, follow.tick(ctx));
        assertEquals(40, ctx.mover.lastX);

        ctx.percepts.position = new Pos(39, 64, 0);
        ctx.mover.setState(MoveState.ARRIVED);
        follow.tick(ctx);

        assertEquals(TaskStatus.FAILED, follow.tick(ctx));
    }

    @Test
    void withNowhereToMeetLosingSightFails() {
        assertEquals(TaskStatus.FAILED, new Follow(leader, null).tick(new FakeContext()));
    }

    @Test
    void theWaitEndsWhenEveryoneIsNear() {
        FakeContext ctx = new FakeContext();
        WaitForCompany wait = new WaitForCompany(Set.of(leader), 16, 600);
        leaderAt(ctx, 30, 0);

        assertEquals(TaskStatus.RUNNING, wait.tick(ctx));
        leaderAt(ctx, 10, 0);
        assertEquals(TaskStatus.SUCCESS, wait.tick(ctx));
    }

    @Test
    void theWaitEndsWhenTheTimeIsUp() {
        FakeContext ctx = new FakeContext();
        WaitForCompany wait = new WaitForCompany(Set.of(leader), 16, 3);

        for (int tick = 0; tick < 3; tick++) {
            assertEquals(TaskStatus.RUNNING, wait.tick(ctx));
        }
        assertEquals(TaskStatus.SUCCESS, wait.tick(ctx));
        assertEquals(0, wait.remaining());
    }
}
