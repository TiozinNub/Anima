package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Striker;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Engage}, the loop inside {@link Fight}: chase, wait out the charge, swing, until dead. */
class EngageTest {

    private final FakeContext ctx = new FakeContext();

    private Being zombieAt(int x, double distance) {
        Being zombie = FakePercepts.monsterAt(new Pos(x, 64, 0), distance, true);
        ctx.percepts.beings = List.of(zombie);
        return zombie;
    }

    private static Being movedTo(Being who, int x, double distance) {
        return new Being(who.id(), who.kind(), who.species(), who.name(), who.profession(),
                new Pos(x, 64, 0), distance, who.eyeHeight(), who.playerControlled(),
                who.count(), who.spread(), who.herdAnimal(), who.members(), who.activity(),
                who.locomotion(), who.sneaking(), who.watching(), who.aimedAt(), who.hailing(),
                who.approaching(), who.aggressive(), who.gear(), who.identified(),
                who.awareness(), who.held());
    }

    private static final int QUICKEST = 4;
    private static final int SLOWEST = 10;

    /** Ticks until the next blow lands, or -1 within {@code limit}. */
    private int ticksToNextBlow(Engage engage, int limit) {
        int before = ctx.striker.struck.size();
        for (int t = 1; t <= limit; t++) {
            engage.tick(ctx);
            if (ctx.striker.struck.size() > before) {
                return t;
            }
        }
        return -1;
    }

    @Test
    void inReachAndChargedItSwingsAfterAHumansReactionNotTheSameTick() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.IN_REACH;
        Engage engage = new Engage(zombie.id(), zombie.pos());

        int ticks = ticksToNextBlow(engage, 20);
        assertTrue(ticks - 1 >= QUICKEST && ticks - 1 <= SLOWEST,
                "the blow waits " + (ticks - 1) + " ticks after it became possible");
        assertEquals(List.of(zombie.id()), ctx.striker.struck);
        assertEquals(0, ctx.mover.moveToCalls, "nothing to chase at arm's length");
    }

    @Test
    void theReactionVariesFromBlowToBlow() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.IN_REACH;
        Engage engage = new Engage(zombie.id(), zombie.pos());
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int blow = 0; blow < 30; blow++) {
            int ticks = ticksToNextBlow(engage, 20);
            assertTrue(ticks - 1 >= QUICKEST && ticks - 1 <= SLOWEST, "blow " + blow + ": " + ticks);
            seen.add(ticks);
        }
        assertTrue(seen.size() > 1, "a metronome is as easy to read as a machine: " + seen);
    }

    @Test
    void itStepsInsideItsFullReachBeforeSwinging() {
        Being zombie = zombieAt(3, 3.0);
        ctx.striker.gap = 3.0; // the very edge of a 3-block reach
        Engage engage = new Engage(zombie.id(), zombie.pos());

        assertEquals(-1, ticksToNextBlow(engage, 15), "no blow from the edge of reach");
        assertTrue(ctx.mover.moveToCalls > 0, "it closes in instead");

        ctx.striker.gap = 2.3; // well inside, whatever the roll
        assertTrue(ticksToNextBlow(engage, 20) > 0);
    }

    @Test
    void inReachButStillChargingItWaits() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.IN_REACH;
        ctx.striker.charge = 0.6;
        Engage engage = new Engage(zombie.id(), zombie.pos());

        assertEquals(TaskStatus.RUNNING, engage.tick(ctx));
        assertTrue(ctx.striker.struck.isEmpty(), "a swing at 0.6 charge is a weak one; wait it out");
    }

    @Test
    void itDrawsBeforeTheChaseSoTheWarmupRunsOnTheWay() {
        Being zombie = zombieAt(8, 8.0);
        new Engage(zombie.id(), zombie.pos()).tick(ctx);

        assertEquals(1, ctx.striker.draws);
        assertEquals(1, ctx.mover.moveToCalls);
    }

    @Test
    void aHandThatJustChangedDoesNotSwingThatTick() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.IN_REACH;
        ctx.striker.drawChanges = true;
        Engage engage = new Engage(zombie.id(), zombie.pos());

        engage.tick(ctx);
        assertTrue(ctx.striker.struck.isEmpty(),
                "the counter still reads the old hand's charge until the body's next tick");
        assertEquals(1, ctx.striker.draws, "ranked once, not again before the blow");
        assertTrue(ticksToNextBlow(engage, 20) > 0);
        assertEquals(2, ctx.striker.draws, "and once more when the blow opened");
    }

    @Test
    void everyBlowAsksForTheBestWeaponFirst() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.IN_REACH;
        Engage engage = new Engage(zombie.id(), zombie.pos());
        ticksToNextBlow(engage, 20);
        int draws = ctx.striker.draws;
        ctx.striker.drawChanges = true; // the last blow broke the sword; a spare comes out
        engage.tick(ctx);

        assertEquals(1, ctx.striker.struck.size(), "no blow on the tick the spare was drawn");
        assertEquals(draws + 1, ctx.striker.draws, "the next blow asked for the best weapon");
        assertTrue(ticksToNextBlow(engage, 20) > 0, "and the spare swings once it has charged");
    }

    @Test
    void itWatchesTheTargetAtWorkRank() {
        Being zombie = zombieAt(5, 5.0);
        new Engage(zombie.id(), zombie.pos()).tick(ctx);

        assertEquals(Gazer.Priority.WORK, ctx.gazer.priority,
                "the chase's own NAV glance must not pull the eyes off the target");
        assertEquals(5.5, ctx.gazer.x, 1e-9);
        assertEquals(64 + zombie.eyeHeight(), ctx.gazer.y, 1e-9);
    }

    @Test
    void outOfReachItChasesTheirCellAtAWalkWhenNear() {
        Being zombie = zombieAt(5, 5.0);
        new Engage(zombie.id(), zombie.pos()).tick(ctx);

        assertEquals(List.of("moveTo(5, 64, 0)"), ctx.mover.events);
        assertEquals(Gait.WALK, ctx.mover.lastGait, "a walk arrives able to sweep");
    }

    @Test
    void farAwayTheChaseSprints() {
        Being zombie = zombieAt(10, 10.0);
        new Engage(zombie.id(), zombie.pos()).tick(ctx);

        assertEquals(Gait.SPRINT, ctx.mover.lastGait);
    }

    @Test
    void somethingInTheWayIsChasedAroundLikeDistance() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.BLOCKED;
        Engage engage = new Engage(zombie.id(), zombie.pos());

        assertEquals(TaskStatus.RUNNING, engage.tick(ctx));
        assertEquals(1, ctx.mover.moveToCalls);
        assertTrue(ctx.striker.struck.isEmpty(), "never swing at a wall");
    }

    @Test
    void theChaseIsReAimedWhenTheyMove() {
        Being zombie = zombieAt(5, 5.0);
        Engage engage = new Engage(zombie.id(), zombie.pos());
        engage.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        ctx.percepts.beings = List.of(movedTo(zombie, 7, 7.0));
        engage.tick(ctx);

        assertEquals(List.of("moveTo(5, 64, 0)", "stop", "moveTo(7, 64, 0, sprint)"),
                ctx.mover.events);
    }

    @Test
    void reachingThemStopsTheLegAndSwings() {
        Being zombie = zombieAt(5, 5.0);
        Engage engage = new Engage(zombie.id(), zombie.pos());
        engage.tick(ctx);
        ctx.striker.reach = Striker.Reach.IN_REACH;
        assertTrue(ticksToNextBlow(engage, 20) > 0);

        assertEquals(List.of("moveTo(5, 64, 0)", "stop"), ctx.mover.events);
        assertEquals(1, ctx.striker.struck.size());
    }

    @Test
    void aDyingTargetIsSuccessWhoeverKilledIt() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.DEAD;

        assertEquals(TaskStatus.SUCCESS, new Engage(zombie.id(), zombie.pos()).tick(ctx));
    }

    @Test
    void aTargetThatLeftTheWorldFails() {
        Being zombie = zombieAt(2, 2.0);
        ctx.striker.reach = Striker.Reach.GONE;
        Engage engage = new Engage(zombie.id(), zombie.pos());

        assertEquals(TaskStatus.FAILED, engage.tick(ctx));
        assertEquals("they are gone", engage.failureDetail());
    }

    @Test
    void aTargetOutOfThePerceptsFails() {
        Being zombie = zombieAt(5, 5.0);
        ctx.percepts.beings = List.of();
        Engage engage = new Engage(zombie.id(), zombie.pos());

        assertEquals(TaskStatus.FAILED, engage.tick(ctx));
        assertEquals("lost track of them", engage.failureDetail());
    }

    @Test
    void legsThatEndWithoutGainingGroundGiveUp() {
        Being zombie = zombieAt(5, 5.0);
        ctx.mover.setState(MoveState.ARRIVED); // every leg ends at once, never any closer
        Engage engage = new Engage(zombie.id(), zombie.pos());

        TaskStatus status = TaskStatus.RUNNING;
        int ticks = 0;
        while (status == TaskStatus.RUNNING && ticks < 20) {
            status = engage.tick(ctx);
            ticks++;
        }
        assertEquals(TaskStatus.FAILED, status);
        assertEquals("could not get to them", engage.failureDetail());
        assertEquals(Engage.FRUITLESS_LIMIT, ctx.mover.moveToCalls);
    }

    @Test
    void legsThatGainGroundKeepGoing() {
        Being zombie = zombieAt(12, 12.0);
        ctx.mover.setState(MoveState.ARRIVED);
        Engage engage = new Engage(zombie.id(), zombie.pos());

        for (int i = 0; i < 20; i++) {
            // Each leg ends a block nearer than it began, though their cell never changes.
            ctx.percepts.beings = List.of(movedTo(zombie, 12, 12.0 - i * 0.5));
            assertEquals(TaskStatus.RUNNING, engage.tick(ctx), "tick " + i);
        }
    }

    @Test
    void fightDecomposesIntoEngagingTheSameTarget() {
        Being zombie = zombieAt(2, 2.0);
        Fight fight = new Fight(zombie.id(), zombie.pos());
        List<Task> steps = fight.methods().get(0).decompose(ctx);

        Engage engage = assertInstanceOf(Engage.class, steps.get(0));
        assertEquals(zombie.id(), engage.target());
    }
}
