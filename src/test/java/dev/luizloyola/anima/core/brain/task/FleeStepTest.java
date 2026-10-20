package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link FleeStep}: the escape vector runs away from the proximity-weighted threat centroid,
 * closer threats dominating via {@code 1/distance²}. Driven through the real
 * {@link TaskExecutor}.
 */
class FleeStepTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private static Being threatAt(int x, int z, double distance, boolean approaching) {
        return FakePercepts.monsterAt(new Pos(x, 64, z), distance, approaching);
    }

    @Test
    void runsAwayFromASingleThreatDueEast() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false));
        executor.run(new FleeStep(), ctx.seed(new Random(1)));
        executor.tick(ctx); // expand FleeStep -> Escape.decompose -> GoTo issues moveTo

        assertTrue(ctx.mover.lastX < 0, "they run west, away from the eastern threat");
        assertEquals(64, ctx.mover.lastY, "y is unchanged");
        double length = Math.hypot(ctx.mover.lastX, ctx.mover.lastZ);
        assertTrue(Math.abs(length - FleeStep.FLEE_LEG) <= FleeStep.JITTER * Math.sqrt(2) + 1,
                "leg length ~= FLEE_LEG (" + FleeStep.FLEE_LEG + ") +/- jitter, was " + length);
    }

    @Test
    void escapeVectorIsDominatedByTheCloserThreat() {
        ctx.percepts.position = new Pos(0, 64, 0);
        // Close threat due east; far threat due north. 1/d^2 weighting should make the close
        // eastern threat dominate the centroid, so they run west and (slightly) south.
        ctx.percepts.beings = List.of(
                threatAt(4, 0, 4.0, false),
                threatAt(0, 12, 12.0, false));
        executor.run(new FleeStep(), ctx.seed(new Random(2)));
        executor.tick(ctx);

        assertTrue(ctx.mover.lastX < 0, "dominated by the close eastern threat -> runs west");
        assertTrue(ctx.mover.lastZ < 0, "the far northern threat still pulls the aim slightly south");
    }

    @Test
    void surroundedStillProducesAFullLengthLeg() {
        ctx.percepts.position = new Pos(0, 64, 0);
        // Two threats symmetric about them -> the weighted centroid lands on that position.
        ctx.percepts.beings = List.of(
                threatAt(8, 0, 8.0, false),
                threatAt(-8, 0, 8.0, false));
        executor.run(new FleeStep(), ctx.seed(new Random(3)));
        executor.tick(ctx);

        double length = Math.hypot(ctx.mover.lastX, ctx.mover.lastZ);
        assertTrue(length > FleeStep.FLEE_LEG - FleeStep.JITTER * Math.sqrt(2) - 1,
                "surrounded still produces a full-length leg (any direction), was " + length);
        assertEquals(64, ctx.mover.lastY);
    }

    @Test
    void decomposeEmitsExactlyOneUrgentGoToAndEndsWithNoIdlePause() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false));
        executor.run(new FleeStep(), ctx.seed(new Random(4)));
        executor.tick(ctx); // issues the one GoTo

        assertEquals(1, ctx.mover.moveToCalls, "exactly one GoTo issued");
        assertEquals(Gait.SPRINT, ctx.mover.lastGait, "the leg sprints where it can — the flee gait");

        ctx.mover.setState(MoveState.ARRIVED);
        executor.tick(ctx); // GoTo SUCCEEDS
        executor.tick(ctx); // the threat is in view, so the look back has nothing to look for
        assertFalse(executor.isBusy(), "SUCCESS just ends the leg -- unlike WanderStep, no Idle pause");
        assertFalse(ctx.gazer.snap, "no look back at something in view");
        assertEquals(1, ctx.mover.moveToCalls, "still exactly one GoTo -- nothing chained within one step");
    }

    @Test
    void decomposeReadsTheCurrentPositionAndThreatsNotTheGrantTimeOnes() {
        FleeStep step = new FleeStep();
        ctx.seed(new Random(5));
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false)); // east, at grant time
        executor.run(step, ctx); // installed while at spawn...

        ctx.percepts.position = new Pos(100, 70, 200); // ...but they walked before it expanded
        ctx.percepts.beings = List.of(threatAt(100, 210, 10.0, false)); // and the threat moved too (now north)

        executor.tick(ctx); // lazy expansion reads the CURRENT percepts
        assertEquals(70, ctx.mover.lastY, "offset from the CURRENT cell");
        assertTrue(ctx.mover.lastZ < 200, "runs away from the CURRENT threat position (south), not the stale one");
    }

    /** One of a fresh flee step's ways, by what it calls itself. */
    static Method method(String name) {
        return new FleeStep().methods().stream().filter(m -> m.describe().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void describeReadsAsFleeStep() {
        assertEquals("flee step", new FleeStep().describe());
    }

    @Test
    void aFlightDownAHillRunsToTheFloor() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.fallsAwayFrom(3, 4);
        ctx.percepts.beings = List.of(threatAt(-10, 0, 10.0, false));
        executor.run(new FleeStep(), ctx.seed(new Random(1)));
        executor.tick(ctx);

        assertTrue(ctx.mover.lastX > 3, "east, away from the western threat");
        assertEquals(60, ctx.mover.lastY, "the legs lower a goal one cell at most (Luiz, 2026-10-02)");
    }

    // ── a flight leans toward home ───────────────────────────────────────────────────────────

    private static final double PULL = 0.003;
    private static final Pos HOME = new Pos(0, 64, -60);

    private void livesAt(Pos home) {
        ctx.depot = java.util.Optional.of(new dev.luizloyola.anima.core.store.Depot.Site(home,
                java.util.Set.of(dev.luizloyola.anima.core.territory.ChunkKey.at(
                        "minecraft:overworld", home.x(), home.z()))));
    }

    /** Where one leg from the origin runs, a zombie due east, the stream pinned. */
    private Pos legFrom(long seed) {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(threatAt(10, 0, 10.0, false));
        executor.run(new FleeStep(), ctx.seed(new Random(seed)));
        executor.tick(ctx);
        return new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
    }

    @Test
    void aFlightLeansTowardHomeWhenNothingElseTellsTheWaysApart() {
        ctx.profile = dev.luizloyola.anima.core.agent.TestSpecies.with(
                dev.luizloyola.anima.core.agent.ProfileAspect.FLEE_HOME_PULL, PULL);
        livesAt(HOME);
        Pos leg = legFrom(1);
        assertTrue(leg.x() < 0, "still away from the zombie: " + leg);
        assertTrue(leg.z() <= -6, "home is north, and the leg leans that way: " + leg);
    }

    @Test
    void aRealDangerStillOutweighsTheWayHome() {
        ctx.profile = dev.luizloyola.anima.core.agent.TestSpecies.with(
                dev.luizloyola.anima.core.agent.ProfileAspect.FLEE_HOME_PULL, PULL);
        livesAt(HOME);
        long now = 10_000L;
        ctx.percepts.time = now;
        Pos creeper = new Pos(-8, 64, -8);
        ctx.knowledge.note(new dev.luizloyola.anima.core.brain.knowledge.PoiMemory(
                        dev.luizloyola.anima.core.brain.knowledge.PoiKind.DANGER, "creeper",
                        java.util.UUID.randomUUID(), creeper,
                        dev.luizloyola.anima.core.brain.knowledge.Region.of(creeper), 1, false, now),
                dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge.maxPerKind(
                        dev.luizloyola.anima.core.agent.TestSpecies.PROFILE));
        Pos leg = legFrom(1);
        double fromCreeper = Math.hypot(leg.x() - creeper.x(), leg.z() - creeper.z());
        assertTrue(fromCreeper > 6.0, "ran home past the creeper, ending " + fromCreeper
                + " blocks from it: " + leg);
    }

    @Test
    void withNoHomeTheFlightIsAsItWas() {
        Pos plain = legFrom(7);
        ctx.mover.lastX = 0;
        ctx.mover.lastZ = 0;
        ctx.profile = dev.luizloyola.anima.core.agent.TestSpecies.with(
                dev.luizloyola.anima.core.agent.ProfileAspect.FLEE_HOME_PULL, PULL);
        assertEquals(plain, legFrom(7), "a pull with nowhere to pull to changes nothing");
    }

    @Test
    void theShelterNearestHomeIsTheRefugeAndAFarOneIsNot() {
        livesAt(HOME);
        assertEquals(HOME, Refuge.of(ctx).orElseThrow(), "nothing remembered: home itself");
        Pos far = new Pos(0, 64, 40);
        Pos near = new Pos(20, 64, -60);
        for (Pos in : List.of(far, near)) {
            ctx.knowledge.note(new dev.luizloyola.anima.core.brain.knowledge.PoiMemory(
                    dev.luizloyola.anima.core.brain.knowledge.PoiKind.SHELTER,
                    in.x() + " " + in.y() + " " + (in.z() - 1), in,
                    dev.luizloyola.anima.core.brain.knowledge.Region.of(in), 1, false, 0L), 16);
        }
        assertEquals(near, Refuge.of(ctx).orElseThrow());
    }
}
