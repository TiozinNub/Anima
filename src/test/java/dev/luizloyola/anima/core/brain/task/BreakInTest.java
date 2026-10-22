package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.history.Deed;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A player or a party member talking breaks in (breaking-in spec): over work at any priority and a
 * mild drive, never over danger or a strong need, and a stranger still waits for the boundary.
 */
class BreakInTest {

    private final FakeContext ctx = new FakeContext();

    private static final class Drive implements Instinct {
        final String name;
        double pressure;
        boolean breaksIn;
        boolean urgent;
        /** Ticks each root runs before it succeeds; negative runs for ever. */
        int lasts = -1;
        int roots;

        Drive(String name, double pressure) {
            this.name = name;
            this.pressure = pressure;
        }

        @Override
        public double pressure(BrainContext c) {
            return pressure;
        }

        @Override
        public Task root(BrainContext c) {
            roots++;
            return lasts < 0 ? new Forever() : new Steps(lasts);
        }

        @Override
        public boolean breaksIn(BrainContext c) {
            return breaksIn;
        }

        @Override
        public boolean urgent(BrainContext c) {
            return urgent;
        }

        @Override
        public Deed doing(BrainContext c) {
            return Deed.of(FakeDoings.IDLED);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private static final class Forever implements PrimitiveTask {
        @Override
        public TaskStatus tick(BrainContext c) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "forever";
        }
    }

    private static final class Steps implements PrimitiveTask {
        int left;

        Steps(int left) {
            this.left = left;
        }

        @Override
        public TaskStatus tick(BrainContext c) {
            return --left <= 0 ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "steps";
        }
    }

    private static final class Job implements WorkItem {
        final double priority;

        Job(double priority) {
            this.priority = priority;
        }

        @Override
        public double priority() {
            return priority;
        }

        @Override
        public Task root() {
            return new Forever();
        }

        @Override
        public Deed doing() {
            return Deed.of(FakeDoings.DID_IT);
        }

        @Override
        public String describe() {
            return "keep with the leader";
        }
    }

    private static final class Board implements WorkSource {
        WorkItem offered;

        @Override
        public Optional<WorkItem> bestAvailable(BrainContext c) {
            return Optional.ofNullable(offered);
        }

        @Override
        public void claimed(WorkItem item, BrainContext c) {
        }

        @Override
        public void completed(WorkItem item, BrainContext c) {
        }

        @Override
        public void failed(WorkItem item, BrainContext c) {
        }
    }

    private final Drive talk = new Drive("converse", 0.0);
    private final Drive eat = new Drive("eat", 0.0);
    private final Drive flee = new Drive("flee", 0.0);
    private final Board board = new Board();
    private final Arbiter arbiter = new Arbiter(List.of(flee, eat, talk), board);

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            arbiter.tick(ctx);
        }
    }

    private void working() {
        board.offered = new Job(0.6);
        ticks(2);
        assertTrue(arbiter.grant().workRunning(), "at work");
    }

    private void calledBy(boolean playerOrParty) {
        talk.pressure = 0.55;
        talk.breaksIn = playerOrParty;
    }

    @Test
    void aPlayerBreaksIntoWorkAtAnyPriorityAndTheErrandIsKept() {
        working();
        calledBy(true);
        talk.lasts = 3;
        ticks(1);
        assertEquals(Optional.of(talk), arbiter.activeDrive(), "talking");
        assertFalse(arbiter.grant().workRunning(), "the errand is set aside, not dropped");
        talk.pressure = 0.0; // the conversation ends
        ticks(4);
        assertTrue(arbiter.grant().workRunning(), "and taken up again after the talk");
    }

    @Test
    void aStrangerStillWaitsForTheBoundary() {
        working();
        calledBy(false);
        ticks(3);
        assertTrue(arbiter.grant().workRunning(), "still at work");
        assertFalse(arbiter.yieldsTo(0.55, ctx), "and a stranger's tap is told busy");
        assertTrue(arbiter.yieldsTo(0.55, true, ctx), "a player's is not");
    }

    @Test
    void aMildNeedIsBrokenIntoAndPaysNoCooldown() {
        eat.pressure = 0.4;
        ticks(2);
        calledBy(true);
        ticks(1);
        assertEquals(Optional.of(talk), arbiter.activeDrive());
        assertFalse(arbiter.cooldowns().containsKey(eat.key()), "being talked to is no failure");
    }

    @Test
    void aStrongNeedIsNeverBrokenInto() {
        eat.pressure = 0.7;
        ticks(2);
        calledBy(true);
        ticks(3);
        assertEquals(Optional.of(eat), arbiter.activeDrive(), "at the preempt bar, eating holds");
        assertFalse(arbiter.yieldsTo(0.55, true, ctx), "so a tap is told busy");
    }

    @Test
    void dangerIsNeverBrokenInto() {
        flee.pressure = 0.3;
        flee.urgent = true;
        ticks(2);
        calledBy(true);
        ticks(3);
        assertEquals(Optional.of(flee), arbiter.activeDrive(), "running for their life holds");
        assertFalse(arbiter.yieldsTo(0.55, true, ctx));
    }

    @Test
    void dangerBiddingAtTheSameTimeGoesFirst() {
        working();
        calledBy(true);
        flee.pressure = 0.3;
        flee.urgent = true;
        ticks(1);
        assertEquals(Optional.of(flee), arbiter.activeDrive());
    }

    @Test
    void anIdleBodyAnswersAPlayerBeforeTakingWork() {
        board.offered = new Job(0.6);
        calledBy(true);
        ticks(1);
        assertEquals(Optional.of(talk), arbiter.activeDrive(), "work at 0.6 outbids 0.55, yet the player is answered");
    }
}
