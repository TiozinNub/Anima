package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentModifiers;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.AspectModifier;
import dev.luizloyola.anima.core.agent.ModifiedProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.CrescentSampler;
import dev.luizloyola.anima.core.brain.knowledge.FakeGrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Walking a box until it is known.
 *
 * <p>The rule under test was inferred rather than decided: a cell clears by being walked near —
 * the near field individuates whatever stands in it — or by a look that found nothing of the
 * kind sought. Anything glimpsed has to be walked to.
 */
class SurveyAreaTest {

    /**
     * What the sweep is after. Anima ships no botany: without a growth rule registered, a ray
     * lands on an oak, finds nothing that grows and reports nothing — so a test that skipped
     * this would call ground with a tree in it clear, and pass.
     */
    private static final PoiKind SOUGHT = FakeGrowthRule.THICKET;

    @BeforeEach
    void registerWhatGrows() {
        FakeGrowthRule.register();
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    /** Two cells by two — small enough to reason about every one of them. */
    private static Region smallBox() {
        return new Region(new Pos(0, 63, 0), new Pos(15, 70, 15));
    }

    /** Six cells by six, so a look can rule out ground no walk has been near. */
    private static Region wideBox() {
        return new Region(new Pos(0, 63, 0), new Pos(47, 70, 47));
    }

    /**
     * Four cells by four — small enough that one look from the middle settles all of it. A look
     * credits a cell {@code 1 - d/horizon} and {@link SurveyArea#ENOUGH} is half, so it reaches 24
     * of the 48-block horizon these tests lend, against 17 to the far corner. A wider box leaves
     * corners walked for reasons unrelated to what stands in them — the confound the A/B avoids.
     */
    private static Region midBox() {
        return new Region(new Pos(0, 63, 0), new Pos(31, 70, 31));
    }

    /**
     * A body that can actually look up from its feet: the shared fixture species declares
     * {@code places.horizon_radius} and {@code places.near_radius} of ZERO, so
     * {@link dev.luizloyola.anima.core.brain.knowledge.Survey#possible} is false and the sweep
     * degrades to a pure walk (tested below).
     */
    private static AgentProfile seeing() {
        AgentModifiers eyes = new AgentModifiers();
        eyes.apply(AspectModifier.add("test:horizon", ProfileAspect.PLACES_HORIZON_RADIUS, 48));
        eyes.apply(AspectModifier.add("test:halo", ProfileAspect.PLACES_NEAR_RADIUS, 6));
        return ModifiedProfile.of(TestSpecies.PROFILE, eyes);
    }

    private static FakeContext standing(Pos where) {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = where;
        return ctx;
    }

    private static FakeContext looking(Pos where) {
        FakeContext ctx = standing(where);
        ctx.profile = seeing();
        return ctx;
    }

    /** Runs the sweep until it ends or the tick budget runs out, arriving at every walk ordered. */
    private static TaskStatus run(SurveyArea sweep, FakeContext ctx, int ticks) {
        return run(sweep, ctx, ticks, new ArrayList<>());
    }

    /** As above, recording every cell the body was actually made to stand in. */
    private static TaskStatus run(SurveyArea sweep, FakeContext ctx, int ticks, List<Pos> trail) {
        TaskStatus status = TaskStatus.RUNNING;
        for (int tick = 0; tick < ticks && status == TaskStatus.RUNNING; tick++) {
            status = sweep.tick(ctx);
            if (ctx.mover.moveToCalls > 0 && ctx.mover.state() != MoveState.ARRIVED) {
                // The legs are somebody else's problem: a walk ordered is a walk that lands, and
                // the body is where it was told to be from the next tick on.
                ctx.percepts.position = new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ);
                trail.add(ctx.percepts.position);
                ctx.mover.setState(MoveState.ARRIVED);
            } else if (ctx.mover.state() == MoveState.ARRIVED) {
                ctx.mover.setState(MoveState.IDLE);
            }
        }
        return status;
    }

    @Test
    void aBoxDividesIntoCellsOfTheGlimpseGrid() {
        SurveyArea sweep = new SurveyArea(smallBox(), SOUGHT);
        assertEquals(4, sweep.cells(), "16 blocks a side at " + SurveyArea.CELL + " per cell");
        assertEquals(0, sweep.cellsKnown());
    }

    @Test
    void openGroundIsRuledOutByLookingRatherThanByWalking() {
        FakeContext ctx = looking(new Pos(24, 63, 24));
        SurveyArea sweep = new SurveyArea(wideBox(), SOUGHT);
        assertEquals(TaskStatus.SUCCESS, run(sweep, ctx, 20_000));
        assertEquals(sweep.cells(), sweep.cellsKnown());
        // 36 cells with nothing in any of them should not cost 36 walks — a body with no eyes
        // has to walk them, which the test below covers.
        assertTrue(ctx.mover.moveToCalls < sweep.cells(),
                "an empty box cost " + ctx.mover.moveToCalls + " walks for " + sweep.cells()
                        + " cells — looking bought nothing. Note the margin here is thin by "
                        + "design: once the survey's blind ring is excluded, a look only settles "
                        + "cells in the band beyond it, so most of a box is still walked.");
    }

    @Test
    void aBodyThatCannotLookWalksTheWholeBoxAndStillFinishes() {
        // places.horizon_radius of zero: Survey.possible() is false, so every cell has to be
        // visited. It must not deadlock — which it would if walking taught a zero-halo body nothing.
        FakeContext ctx = standing(new Pos(0, 63, 0));
        SurveyArea sweep = new SurveyArea(smallBox(), SOUGHT);
        assertEquals(TaskStatus.SUCCESS, run(sweep, ctx, 4000));
        assertEquals(sweep.cells(), sweep.cellsKnown());
    }

    @Test
    void aCellSomethingWasGlimpsedInMustBeWalkedInto() {
        // The same corner twice, differing only in whether anything stands in it: a look rules out
        // empty ground, a look that saw something rules out nothing.
        // The spot sits in the band a look can settle — past the survey's blind ring plus a cell's
        // reach, inside the range the falloff credits. Nearer it is walked whatever is there;
        // further, walked because the look is not trusted that far.
        Pos middle = new Pos(24, 63, 24);
        Pos spot = new Pos(44, 63, 36);

        FakeContext empty = looking(middle);
        List<Pos> withoutTree = new ArrayList<>();
        assertEquals(TaskStatus.SUCCESS,
                run(new SurveyArea(wideBox(), SOUGHT), empty, 20_000, withoutTree));
        assertFalse(wentNear(withoutTree, spot),
                "empty ground the look could settle should not have been walked to");

        FakeContext wooded = looking(middle);
        wooded.percepts.blocks.placeOak(spot.x(), spot.z());
        List<Pos> withTree = new ArrayList<>();
        assertEquals(TaskStatus.SUCCESS,
                run(new SurveyArea(wideBox(), SOUGHT), wooded, 20_000, withTree));
        assertTrue(wentNear(withTree, spot),
                "a glimpse is not an anchor — somebody has to go and stand next to it");
    }

    /** Whether any walk in the trail landed inside the coverage cell holding this spot. */
    private static boolean wentNear(List<Pos> trail, Pos spot) {
        for (Pos step : trail) {
            if (Math.abs(step.x() - spot.x()) <= SurveyArea.CELL / 2
                    && Math.abs(step.z() - spot.z()) <= SurveyArea.CELL / 2) {
                return true;
            }
        }
        return false;
    }

    @Test
    void groundTooCloseForTheSurveyToReportOnIsNotRuledOutByIt() {
        // The gap that let a box be called clear with a tree standing in it: a look reports
        // NOTHING nearer than places.radius, which belongs to the near field, so silence about a
        // cell inside that ring is not evidence. The tree sits there — too close to be glimpsed,
        // too far for the halo.
        Pos middle = new Pos(16, 63, 16);
        FakeContext ctx = looking(middle);
        int blind = CrescentSampler.radius(ctx.profile());
        int halo = CrescentSampler.nearRadius(ctx.profile());
        Pos hidden = new Pos(middle.x() + (halo + blind) / 2, 63, middle.z());
        assertTrue(horizontal(middle, hidden) > halo && horizontal(middle, hidden) < blind,
                "the fixture must put the tree in the ring, not outside it");
        ctx.percepts.blocks.placeOak(hidden.x(), hidden.z());

        List<Pos> trail = new ArrayList<>();
        assertEquals(TaskStatus.SUCCESS, run(new SurveyArea(midBox(), SOUGHT), ctx, 20_000, trail));
        assertTrue(wentNear(trail, hidden),
                "a cell the survey is blind to must be walked, never assumed empty");
    }

    private static double horizontal(Pos from, Pos to) {
        double dx = from.x() - to.x();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void walkingPastACellIsEnoughToKnowIt() {
        FakeContext ctx = standing(new Pos(-100, 63, -100));
        SurveyArea sweep = new SurveyArea(smallBox(), SOUGHT);
        sweep.tick(ctx);
        assertEquals(0, sweep.cellsKnown(), "nothing is known from a hundred blocks away");

        ctx.percepts.position = new Pos(3, 63, 3);
        sweep.tick(ctx);
        assertTrue(sweep.cellsKnown() >= 1, "the near field knows the ground it is standing on");
    }

    @Test
    void aCellThatCannotBeReachedIsWrittenOffRatherThanRetriedForever() {
        FakeContext ctx = looking(new Pos(0, 63, 0));
        ctx.percepts.blocks.placeOak(44, 44); // something to insist on going to
        SurveyArea sweep = new SurveyArea(wideBox(), SOUGHT);

        TaskStatus status = TaskStatus.RUNNING;
        for (int tick = 0; tick < 40_000 && status == TaskStatus.RUNNING; tick++) {
            status = sweep.tick(ctx);
            // Every walk fails. Without the write-off this loop never ends, and neither does the
            // project above it — a corner behind a cliff would hold a settlement open forever.
            if (ctx.mover.moveToCalls > 0) {
                ctx.mover.setState(MoveState.FAILED);
            }
        }
        assertEquals(TaskStatus.SUCCESS, status);
        assertEquals(sweep.cells(), sweep.cellsKnown());
    }

    @Test
    void aLookFilesEverySightingAndNotOnlyTheOneItWasAfter() {
        FakeContext ctx = looking(new Pos(24, 63, 24));
        ctx.percepts.blocks.placeOak(30, 30);
        SurveyArea sweep = new SurveyArea(wideBox(), SOUGHT);
        run(sweep, ctx, 20_000);
        // A body that stopped and turned all the way round learned about the whole skyline, not
        // only about the errand it was on.
        assertTrue(ctx.knowledge.glimpseCount() > 0);
    }

    @Test
    void coverageSurvivesBeingWrittenDown() {
        FakeContext ctx = standing(new Pos(3, 63, 3));
        SurveyArea sweep = new SurveyArea(smallBox(), SOUGHT);
        sweep.tick(ctx);
        int known = sweep.cellsKnown();
        assertTrue(known > 0);

        SurveyArea.State saved = sweep.snapshot();
        SurveyArea back = new SurveyArea(saved.area(), saved.looking()).restore(saved);
        assertEquals(known, back.cellsKnown(), "a reloaded sweep must not re-walk known ground");
        assertEquals(sweep.cells(), back.cells());
        assertEquals(SOUGHT, back.looking());
        assertEquals(smallBox(), back.area());
    }

    @Test
    void aReloadedSweepDoesNotStartOverEvenPartwayThrough() {
        FakeContext ctx = looking(new Pos(0, 63, 0));
        ctx.percepts.blocks.placeOak(44, 44);
        SurveyArea sweep = new SurveyArea(wideBox(), SOUGHT);
        for (int tick = 0; tick < 50; tick++) {
            sweep.tick(ctx);
        }
        SurveyArea back = new SurveyArea(sweep.snapshot().area(), SOUGHT).restore(sweep.snapshot());
        assertEquals(sweep.cellsKnown(), back.cellsKnown());
        assertNotEquals(0, back.cellsKnown(), "the test proves nothing if nothing was learned");
    }

    @Test
    void cancellingStopsTheLegs() {
        FakeContext ctx = looking(new Pos(0, 63, 0));
        ctx.percepts.blocks.placeOak(44, 44);
        SurveyArea sweep = new SurveyArea(wideBox(), SOUGHT);
        for (int tick = 0; tick < 2000 && ctx.mover.moveToCalls == 0; tick++) {
            sweep.tick(ctx);
        }
        sweep.cancel(ctx);
        assertTrue(ctx.mover.stopCalls > 0, "a cancelled sweep must not leave the body walking");
    }

    @Test
    void theReadoutSaysHowMuchOfTheBoxIsKnown() {
        SurveyArea sweep = new SurveyArea(smallBox(), SOUGHT);
        assertTrue(sweep.describe().startsWith("survey 0/4"));
    }
}
