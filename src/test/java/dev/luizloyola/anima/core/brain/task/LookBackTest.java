package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LookBack} (combat spec, decision 16): a look only when every threat out of sight is
 * far or quiet, aimed where it was, and a search when it is not there.
 */
class LookBackTest {

    /** {@code instincts.flee_look_ticks} in {@link TestSpecies}. */
    private static final int LOOK = 8;

    private final FakeContext ctx = new FakeContext();
    private final List<String> said = new ArrayList<>();
    private final BeingId zombie = BeingId.of(UUID.randomUUID());

    @BeforeEach
    void body() {
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.journalService.subscribe((who, entry) -> said.add(line(entry)));
    }

    private static String line(Entry entry) {
        return entry.detail();
    }

    private Being zombieAt(int x, int z, Being.Awareness awareness) {
        return FakePercepts.monsterAt(zombie, new Pos(x, 64, z), Math.hypot(x, z), awareness);
    }

    /** Ticks the task through one whole look, asserting it stays running and claims every tick. */
    private void throughOneLook(LookBack look) {
        for (int i = 0; i < LOOK; i++) {
            ctx.gazer.asked = false;
            assertEquals(TaskStatus.RUNNING, look.tick(ctx), "still looking at tick " + i);
            assertTrue(ctx.gazer.asked, "the look is re-asked every tick");
        }
    }

    @Test
    void noLookWhileTheThreatIsInView() {
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.SEEN));
        assertEquals(TaskStatus.SUCCESS, new LookBack().tick(ctx));
        assertFalse(ctx.gazer.asked, "nothing out of sight, nothing to look for");
    }

    @Test
    void noStopForAChaserCloseAndHeard() {
        ctx.percepts.beings = List.of(zombieAt(8, 0, Being.Awareness.HEARD));
        assertEquals(TaskStatus.SUCCESS, new LookBack().tick(ctx));
        assertFalse(ctx.gazer.asked, "its steps place it, and stopping would only let it close");
    }

    @Test
    void oneCloseAndHeardVetoesTheLookForAnotherFarOne() {
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED),
                FakePercepts.monsterAt(BeingId.of(UUID.randomUUID()), new Pos(0, 64, -6), 6.0,
                        Being.Awareness.HEARD));
        assertEquals(TaskStatus.SUCCESS, new LookBack().tick(ctx));
        assertFalse(ctx.gazer.asked);
    }

    @Test
    void looksBackAtAThreatOutOfEarshotAndConfirmsIt() {
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED));
        LookBack look = new LookBack();
        throughOneLook(look);

        assertEquals(Gazer.Priority.WORK, ctx.gazer.priority, "above the legs' look ahead");
        assertTrue(ctx.gazer.snap, "a look back whips round");
        assertEquals(14.5, ctx.gazer.x, 1e-9, "at where it was");
        assertEquals(64 + Being.HUMANOID_EYE_HEIGHT, ctx.gazer.y, 1e-9, "at its eyes");

        // The turned head saw it: still coming, a block nearer.
        ctx.percepts.beings = List.of(zombieAt(13, 0, Being.Awareness.SEEN));
        assertEquals(TaskStatus.SUCCESS, look.tick(ctx));
        assertEquals(List.of("looked back: a zombie is 13 blocks away"), said);
    }

    @Test
    void aSpotPastSightRangeIsStillLookedToward() {
        // Seen 17 back one leg ago, 28 back now: the chaser itself is nearer, on the same bearing.
        ctx.percepts.beings = List.of(zombieAt(28, 0, Being.Awareness.REMEMBERED));
        LookBack look = new LookBack();
        throughOneLook(look);
        assertEquals(28.5, ctx.gazer.x, 1e-9);

        ctx.percepts.beings = List.of(zombieAt(15, 0, Being.Awareness.SEEN));
        assertEquals(TaskStatus.SUCCESS, look.tick(ctx));
        assertEquals(List.of("looked back: a zombie is 15 blocks away"), said);
    }

    @Test
    void aLookThatCannotTellSaysSo() {
        ctx.percepts.beings = List.of(zombieAt(28, 0, Being.Awareness.REMEMBERED));
        LookBack look = new LookBack();
        throughOneLook(look);
        assertEquals(TaskStatus.SUCCESS, look.tick(ctx), "still remembered: no search");
        assertEquals(List.of("looked back: a zombie is out of view"), said);
    }

    @Test
    void aQuietThreatInsideTheEarIsLookedFor() {
        ctx.percepts.beings = List.of(zombieAt(6, 0, Being.Awareness.REMEMBERED));
        LookBack look = new LookBack();
        assertEquals(TaskStatus.RUNNING, look.tick(ctx), "not heard for a while: worth a look");
    }

    @Test
    void aThreatNotWhereItWasIsSearchedForAllRound() {
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED));
        LookBack look = new LookBack();
        throughOneLook(look);

        ctx.percepts.beings = List.of(); // the sense refuted it: its spot was in view and empty
        List<double[]> aims = new ArrayList<>();
        for (int search = 0; search < 2; search++) {
            for (int i = 0; i < LOOK; i++) {
                assertEquals(TaskStatus.RUNNING, look.tick(ctx));
            }
            aims.add(new double[] {ctx.gazer.x - 0.5, ctx.gazer.z - 0.5});
        }
        assertEquals(TaskStatus.SUCCESS, look.tick(ctx));

        for (double[] aim : aims) {
            double bearing = Math.toDegrees(Math.atan2(aim[1], aim[0]));
            assertEquals(120.0, Math.abs(bearing), 1e-6, "a third of a turn from where it was");
        }
        assertTrue(aims.get(0)[1] * aims.get(1)[1] < 0, "one to either side");
        assertEquals(List.of("looked back: a zombie is not where it was", "looking around",
                "no sign of a zombie"), said);
    }

    @Test
    void aSoundEndsTheSearch() {
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED));
        LookBack look = new LookBack();
        throughOneLook(look);
        ctx.percepts.beings = List.of();
        assertEquals(TaskStatus.RUNNING, look.tick(ctx), "searching");

        ctx.percepts.beings = List.of(zombieAt(-7, 7, Being.Awareness.HEARD));
        assertEquals(TaskStatus.SUCCESS, look.tick(ctx), "placed again: fight or flight answers");
        assertEquals("found a zombie", said.get(said.size() - 1));
    }

    @Test
    void noSearchWhileSomethingElseStillPresses() {
        Being other = FakePercepts.monsterAt(BeingId.of(UUID.randomUUID()), new Pos(-5, 64, 0), 5.0,
                Being.Awareness.SEEN);
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED), other);
        LookBack look = new LookBack();
        throughOneLook(look);

        ctx.percepts.beings = List.of(other);
        assertEquals(TaskStatus.SUCCESS, look.tick(ctx), "there is still something to run from");
        assertFalse(said.contains("looking around"));
    }

    @Test
    void aLitFuseInReachIsNoTimeToStop() {
        Being creeper = zombieAt(5, 0, Being.Awareness.REMEMBERED);
        ctx.percepts.beings = List.of(creeper);
        ctx.percepts.combatants.put(zombie,
                new Combatant(20, 20, 0, 0, 0, 0, 0.1, 0.4, 6.0));
        assertEquals(TaskStatus.SUCCESS, new LookBack().tick(ctx));
        assertFalse(ctx.gazer.asked);
    }

    @Test
    void aBodyThatNeverLooksBackDoesNot() {
        ctx.profile = TestSpecies.with(ProfileAspect.FLEE_LOOK_TICKS, 0);
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED));
        assertEquals(TaskStatus.SUCCESS, new LookBack().tick(ctx));
        assertFalse(ctx.gazer.asked);
    }

    @Test
    void aFleeLegEndsWithTheLook() {
        ctx.percepts.beings = List.of(zombieAt(14, 0, Being.Awareness.REMEMBERED));
        TaskExecutor executor = new TaskExecutor();
        executor.run(new FleeStep(), ctx.seed(new java.util.Random(7)));
        executor.tick(ctx); // the sprint is ordered
        assertFalse(ctx.gazer.snap, "running, not looking");
        ctx.mover.setState(dev.luizloyola.anima.core.brain.act.MoveState.ARRIVED);
        executor.tick(ctx); // arrived
        executor.tick(ctx); // the look begins
        assertTrue(executor.isBusy(), "the leg is not over until it has looked");
        assertTrue(ctx.gazer.snap);
        assertEquals(Gazer.Priority.WORK, ctx.gazer.priority);
    }
}
