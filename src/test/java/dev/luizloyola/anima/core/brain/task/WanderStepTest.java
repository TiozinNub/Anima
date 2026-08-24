package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.AsciiWorld;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link WanderStep} — walk chance ({@link WanderStep#WALK_CHANCE}), {@link Gait#STROLL}
 * inside the radius with a never-zero offset, the pause range, the lazy position read at decompose
 * time, determinism, and that every target is somewhere the body could actually stand
 * ({@link Standing}) — through the real {@link TaskExecutor}: targets are read off the
 * {@link FakeMover}, pauses counted as busy {@link Idle} ticks.
 *
 * <p>The draw order (walk-roll, then pause, then target) is class contract here — reordering the
 * draws changes which seeds walk. The footing checks below therefore run over hand-drawn worlds
 * rather than by retuning the flat one the seed-pinned cases stand on.
 */
class WanderStepTest {

    private static final int RADIUS = 8;

    /** The body {@link FakeContext} wanders with — 1.8 tall, jumps 1, drops 3, so a reach of 3. */
    private static final MoveCapabilities BODY = MoveCapabilities.of(TestSpecies.PROFILE);

    private record Beat(boolean walked, Pos target, Gait gait, int pause) {}

    /**
     * Runs one full WanderStep off the shared stream in a fresh context/executor; the counted busy
     * Idle ticks are the uniform pause measure for both beat shapes.
     */
    private static Beat runBeat(RandomGenerator random, Pos start) {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = start;
        return runBeat(random, ctx);
    }

    /** As above, over a hand-drawn world instead of the fixture's flat ground under the feet. */
    private static Beat runBeat(RandomGenerator random, Pos start, NavGrid terrain) {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = start;
        ctx.percepts.terrain = terrain;
        return runBeat(random, ctx);
    }

    /** The seam underneath both: for a beat that has to script more than position and terrain. */
    private static Beat runBeat(RandomGenerator random, FakeContext ctx) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(new WanderStep(RADIUS), ctx.seed(random));
        executor.tick(ctx); // expand; ticks the first primitive (GoTo issue, or Idle's first tick)
        boolean walked = ctx.mover.moveToCalls > 0;
        Pos target = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
        int pause = walked ? 0 : 1; // an idle-only beat's expansion tick already ran Idle once
        if (walked) {
            ctx.mover.setState(MoveState.ARRIVED);
            executor.tick(ctx); // GoTo observes ARRIVED -> SUCCESS; Idle is pending
        }
        while (executor.isBusy()) {
            executor.tick(ctx);
            if (executor.isBusy()) {
                pause++; // busy-after ticks are RUNNING Idle ticks; the SUCCESS tick goes idle
            }
        }
        return new Beat(walked, target, ctx.mover.lastGait, pause);
    }

    @Test
    void mostBeatsIdleAndWalkingBeatsMatchTheTunedChance() {
        RandomGenerator random = new Random(1234);
        int walks = 0;
        for (int i = 0; i < 200; i++) {
            if (runBeat(random, new Pos(0, 64, 0)).walked()) {
                walks++;
            }
        }
        // Deterministic per seed, so this cannot flake; the loose band states the INTENT (a
        // WALK_CHANCE retune outside ~[0.22, 0.38] should trip it and force a conscious look).
        assertTrue(walks >= 45 && walks <= 75,
                "expected roughly WALK_CHANCE=" + WanderStep.WALK_CHANCE + " of 200, got " + walks);
        assertTrue(walks > 0, "walking beats must occur");
    }

    @Test
    void walkingBeatsStrollWithinTheRadiusNeverZeroOffsetFlatY() {
        RandomGenerator random = new Random(1234);
        int seen = 0;
        for (int i = 0; i < 200 && seen < 30; i++) {
            Beat beat = runBeat(random, new Pos(0, 64, 0));
            if (!beat.walked()) {
                continue;
            }
            seen++;
            int dx = beat.target().x();
            int dz = beat.target().z();
            assertTrue(Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS,
                    "offset (" + dx + ", " + dz + ") must be within +/-" + RADIUS);
            assertFalse(dx == 0 && dz == 0, "a walking beat always goes somewhere");
            assertEquals(64, beat.target().y(), "y is unchanged — they walk the ground");
            assertEquals(Gait.STROLL, beat.gait(), "wandering ambles; it does not march");
        }
        assertTrue(seen >= 30, "stream must produce enough walking beats to pin");
    }

    @Test
    void pausesRunTheTunedRangeForBothShapes() {
        RandomGenerator random = new Random(99);
        boolean sawIdleOnly = false;
        boolean sawWalking = false;
        for (int i = 0; i < 40; i++) {
            Beat beat = runBeat(random, new Pos(0, 64, 0));
            sawIdleOnly |= !beat.walked();
            sawWalking |= beat.walked();
            assertTrue(beat.pause() >= WanderStep.IDLE_MIN
                            && beat.pause() < WanderStep.IDLE_MIN + WanderStep.IDLE_RANGE,
                    "pause was " + beat.pause());
        }
        assertTrue(sawIdleOnly && sawWalking, "both beat shapes must occur in 40 draws");
    }

    @Test
    void targetIsOffsetFromTheCurrentPositionNotTheConstructionOne() {
        RandomGenerator random = new Random(7);
        for (int i = 0; i < 200; i++) {
            Beat beat = runBeat(random, new Pos(100, 70, 200));
            if (!beat.walked()) {
                continue;
            }
            assertEquals(70, beat.target().y(), "offset from the CURRENT cell");
            assertTrue(Math.abs(beat.target().x() - 100) <= RADIUS
                            && Math.abs(beat.target().z() - 200) <= RADIUS,
                    "offset from (100,70,200)");
            return;
        }
        throw new AssertionError("stream produced no walking beat to pin");
    }

    /** Same seed, same stream: identical shapes, targets, and pauses — reproducible per Person. */
    @Test
    void deterministicPerSeed() {
        RandomGenerator a = new Random(42);
        RandomGenerator b = new Random(42);
        for (int i = 0; i < 10; i++) {
            Beat beatA = runBeat(a, new Pos(0, 64, 0));
            Beat beatB = runBeat(b, new Pos(0, 64, 0));
            assertEquals(beatA, beatB, "same seed -> same beat #" + i);
        }
    }

    /**
     * Feet at (8, 1, 8) so the whole 17-wide roam box is drawn: an open pond, three bottomless
     * holes and a lava cell, spread out rather than clustered so draws land on them often enough
     * that the rejection path is actually exercised, not merely available.
     *
     * <p>No fraction quoted on purpose — how much of the box these refuse depends on how the ledge
     * rule reads a shoreline for a body that can swim, and that is {@link Standing}'s to move.
     * {@code walked > 0} is what keeps the case from going vacuous either way.
     */
    private static AsciiWorld hostileWorld() {
        return AsciiWorld.of(
                "11111111111111111",
                "11111111111111111",
                "11WWW111111111111",
                "11WWW1111111  111",
                "11WWW1111111  111",
                "11111111111111111",
                "11111111111111111",
                "1111111111  11111",
                "11111111111111111",
                "11111111111111111",
                "111111111111L1111",
                "11111111111111111",
                "1111 111111111111",
                "11111111111111111",
                "11111111111111111",
                "11111111111111111",
                "11111111111111111");
    }

    @Test
    void aWanderNeverTargetsSomewhereItCouldNotStand() {
        AsciiWorld world = hostileWorld();
        RandomGenerator random = new Random(1234);
        int walked = 0;
        for (int i = 0; i < 300; i++) {
            Beat beat = runBeat(random, new Pos(8, 1, 8), world);
            if (!beat.walked()) {
                continue;
            }
            walked++;
            Pos target = beat.target();
            assertTrue(Standing.standable(world, BODY, target.x(), target.y(), target.z()),
                    "wandered to " + target + ", where this body could not stand");
        }
        assertTrue(walked > 0, "the stream must walk sometimes, or this asserts nothing");
    }

    /**
     * Also the pin on {@link dev.luizloyola.anima.core.brain.sense.Percepts#terrain()}'s default,
     * which IS {@link NavGrid#UNKNOWN}: a rig with no terrain sense must never stroll.
     */
    @Test
    void aBodyWithNowhereToStandIdlesInsteadOfWalking() {
        RandomGenerator random = new Random(1234);
        for (int i = 0; i < 60; i++) {
            Beat beat = runBeat(random, new Pos(0, 64, 0), NavGrid.UNKNOWN);
            assertFalse(beat.walked(), "a body that can see no ground has nowhere to walk to");
            assertTrue(beat.pause() >= WanderStep.IDLE_MIN
                            && beat.pause() < WanderStep.IDLE_MIN + WanderStep.IDLE_RANGE,
                    "the beat still runs its full pause; it was " + beat.pause());
        }
    }

    /** An open pond ('W') and a wadeable puddle ('w'): dry land only means both. */
    private static AsciiWorld pondWorld() {
        return AsciiWorld.of(
                "11111111111111111",
                "11111111111111111",
                "11111111111111111",
                "111WWWWW111111111",
                "111WWWWW111111111",
                "111WWWWW111111111",
                "111WWWWW111111111",
                "11111111111111111",
                "11111111111111111",
                "11111111111111111",
                "11111111111111111",
                "11111111111www111",
                "11111111111www111",
                "11111111111111111",
                "11111111111111111",
                "11111111111111111",
                "11111111111111111");
    }

    @Test
    void aWanderNeverTargetsWater() {
        AsciiWorld world = pondWorld();
        RandomGenerator random = new Random(1234);
        int walked = 0;
        for (int i = 0; i < 300; i++) {
            Beat beat = runBeat(random, new Pos(8, 1, 8), world);
            if (!beat.walked()) {
                continue;
            }
            walked++;
            Pos target = beat.target();
            assertNotEquals(CellType.WATER, world.cell(target.x(), target.y(), target.z()),
                    "waded out to " + target + " to stand there for a quarter of a minute");
            assertNotEquals(CellType.WATER, world.cell(target.x(), target.y() - 1, target.z()),
                    "settled on the surface of the water at " + target);
        }
        assertTrue(walked > 0, "the stream must walk sometimes, or this asserts nothing");
    }

    /**
     * The draw-order contract, asserted rather than trusted: a calm beat on good ground spends one
     * {@code (dx, dz)} draw, so adding the footing budget cannot quietly re-baseline every seed in
     * this file. Seed 4096 because {@code Random}'s first {@code nextDouble} sits near 0.73 for
     * every small seed, which never walks.
     */
    @Test
    void aCalmBeatSpendsExactlyOneTargetDraw() {
        RandomGenerator stream = new Random(4096);
        Beat beat = runBeat(stream, new Pos(0, 64, 0));
        assertTrue(beat.walked(), "the seed has to walk for this to pin anything");

        RandomGenerator mirror = new Random(4096);
        mirror.nextDouble(); // the walk roll
        mirror.nextInt(WanderStep.IDLE_RANGE); // the pause
        int dx;
        int dz;
        do {
            dx = mirror.nextInt(2 * RADIUS + 1) - RADIUS;
            dz = mirror.nextInt(2 * RADIUS + 1) - RADIUS;
        } while (dx == 0 && dz == 0);

        assertEquals(new Pos(dx, 64, dz), beat.target(), "the target IS that first draw");
        assertEquals(mirror.nextLong(), stream.nextLong(),
                "an untroubled beat on good ground draws once and stops");
    }

    /**
     * The other half of the contract, and the half more likely to rot: weighing takes a different
     * branch of {@code wanted}, so a later {@code MAX_ROLLS} retune or one more {@code continue} in
     * the loop would quietly spend more of the stream here while the calm pin above stayed green.
     *
     * <p>The monster is at (14, 64, 14) — nineteen blocks off, so it makes
     * {@link Comfort#worthWeighing} true without tripping {@link Comfort#crowded}, which would
     * force the walk and turn this into a test about crowding instead. Seed 4096 for the reason
     * given above: it is the first that opens on a walk.
     */
    @Test
    void aWeighingBeatSpendsExactlyFourTargetDraws() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(new Pos(14, 64, 14), 19.8, false));
        RandomGenerator stream = new Random(4096);
        Beat beat = runBeat(stream, ctx);
        assertTrue(beat.walked(), "the seed has to walk for this to pin anything");

        RandomGenerator mirror = new Random(4096);
        mirror.nextDouble(); // the walk roll
        mirror.nextInt(WanderStep.IDLE_RANGE); // the pause
        List<Pos> drawn = new ArrayList<>();
        while (drawn.size() < 4) {
            int dx;
            int dz;
            do {
                dx = mirror.nextInt(2 * RADIUS + 1) - RADIUS;
                dz = mirror.nextInt(2 * RADIUS + 1) - RADIUS;
            } while (dx == 0 && dz == 0);
            drawn.add(new Pos(dx, 64, dz));
        }

        assertTrue(drawn.contains(beat.target()),
                "it kept " + beat.target() + ", which is not one of the four it drew: " + drawn);
        assertEquals(mirror.nextLong(), stream.nextLong(),
                "a wary beat on good ground draws four candidates and stops");
    }

    @Test
    void describeReadsAsTheDesignNamesIt() {
        assertEquals("wander step", new WanderStep(RADIUS).describe());
    }
}
