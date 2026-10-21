package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Waypoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Walking home for the night rather than digging in where it stands (Luiz, 2026-10-21): at dusk
 * as far as a walk gets by dark and presses on after; at dark only a short walk; a body away on
 * purpose as before.
 *
 * <p>The body stands at (0, 64, 0); a shelter it remembers lies due south, the cell inside its door
 * at (0, 64, d). A walk covers {@link NightShelterStep#WALK_PACE} a tick, and presses on in the dark
 * for {@link NightShelterStep#pressOn()} — 64.8 blocks.
 */
class WalkHomeForTheNightTest {

    private final FakeContext ctx = new FakeContext();

    @BeforeEach
    void aBodyThatMindsTheDark() {
        ctx.profile = TestSpecies.with(ProfileAspect.NIGHT_SHELTER_PRESSURE, 0.7);
        ctx.percepts.position = new Pos(0, 64, 0);
    }

    // ── choosing ─────────────────────────────────────────────────────────────────────────────

    @Test
    void atDuskAShelterReachedByDarkIsWalkedHomeTo() {
        ctx.percepts.timeOfDay = 12_900L; // 600 ticks of dusk left: 129.6 blocks
        remember(100);
        RunToShelter walk = walkIn();
        assertTrue(walk.homeward(), "weighed against the dark");
        assertTrue(said("walking home for the night, 100 blocks, there by dark"));
    }

    @Test
    void atDuskOneLeavingAShortWalkInTheDarkIsWalkedToo() {
        ctx.percepts.timeOfDay = 12_900L;
        remember(180);
        walkIn();
        assertTrue(said("walking home for the night, 180 blocks, about 50 left at dark"));
    }

    @Test
    void atDuskOneFurtherThanThatIsNot() {
        ctx.percepts.timeOfDay = 12_900L;
        remember(200); // past 129.6 + 64.8
        assertNull(NightShelterStep.shelterToWalkTo(ctx));
        ctx.percepts.timeOfDay = 12_000L; // all of dusk ahead: 324 + 64.8
        assertEquals(200, NightShelterStep.shelterToWalkTo(ctx).anchor().z(), "earlier, it is walked to");
    }

    @Test
    void atDarkOnlyAShortWalkIsSetOutOn() {
        ctx.percepts.timeOfDay = 18_000L;
        remember(70);
        assertNull(NightShelterStep.shelterToWalkTo(ctx), "past what it presses on through");
        remember(60);
        assertEquals(60, NightShelterStep.shelterToWalkTo(ctx).anchor().z());
    }

    @Test
    void aBodyAwayOnPurposeDigsInWhereItIsAsBefore() {
        ctx.percepts.timeOfDay = 12_000L;
        ctx.awayOnPurpose = true;
        remember(100);
        assertNull(NightShelterStep.shelterToWalkTo(ctx), "an expedition is not walked home from");
        remember(60);
        RunToShelter walk = walkIn();
        assertFalse(walk.homeward(), "judged by the reach it always had");
        assertTrue(said("away on purpose, going in for the night at (0, 64, 60), 60 blocks off"));
    }

    @Test
    void whenTheWalkMustStartToBeInByDarkThePullIsFullAtOnce() {
        dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct night =
                new dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct();
        remember(100); // 100 × 1.3 at a walk, and the slack: 702 ticks
        ctx.percepts.timeOfDay = 12_100L; // 1,400 left: work can wait
        assertEquals(0.7 * 100 / 1_500, night.pressure(ctx), 1e-9);
        ctx.percepts.timeOfDay = 12_850L; // 650 left: time to go
        assertEquals(0.7, night.pressure(ctx), 1e-9,
                "full, over the preempt bar: she lost to work at 0.60 until it was already dark");
        ctx.awayOnPurpose = true;
        assertEquals(0.7 * 850 / 1_500, night.pressure(ctx), 1e-9, "a body away on purpose is not called home");
        remember(50);
        ctx.percepts.timeOfDay = 13_200L; // 300 left, a 401-tick walk to a shelter within its old reach
        assertEquals(0.7 * 1_200 / 1_500, night.pressure(ctx), 1e-9, "nor to one near enough to walk into");
        ctx.percepts.timeOfDay = 12_850L;
        ctx.awayOnPurpose = false;
        ctx.percepts.enclosure = new dev.luizloyola.anima.core.brain.sense.Enclosure(
                dev.luizloyola.anima.core.brain.sense.Enclosure.Openness.OPENABLE,
                dev.luizloyola.anima.core.brain.sense.Enclosure.Holes.NONE, true, ctx.percepts.position,
                java.util.Set.of(ctx.percepts.position), List.of(), List.of(), 1L);
        assertEquals(0.7 * 850 / 1_500, night.pressure(ctx), 1e-9, "in already");
    }

    @Test
    void aShelterOutOfReachDoesNotCallItHome() {
        dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct night =
                new dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct();
        remember(300);
        ctx.percepts.timeOfDay = 13_000L; // 500 left: 108 + 64.8 is all it would walk
        assertEquals(0.7 * 1_000 / 1_500, night.pressure(ctx), 1e-9, "it digs in when night wins, not now");
    }

    // ── the way there ────────────────────────────────────────────────────────────────────────

    @Test
    void aWayThereLongerThanTheDarkAllowsIsRefusedForADig() {
        ctx.percepts.timeOfDay = 13_200L; // 300 left: 64.8 + 64.8
        RunToShelter walk = walking(120);
        ctx.mover.route = new Path(detour(), true);
        assertEquals(TaskStatus.FAILED, walk.tick(ctx));
        assertTrue(ctx.knowledge.isAvoided(PoiKind.SHELTER, new Pos(0, 64, 120), ctx.percepts.time + 1_000));
        assertTrue(said("too far to make it home, digging in: 140 blocks by the way there"));
        RunToShelter straight = walking(120);
        ctx.mover.route = new Path(south(1, 120), true);
        assertEquals(TaskStatus.RUNNING, straight.tick(ctx), "120 by the way there: within it");
    }

    @Test
    void aLongWalkHomeIsPlannedLegByLeg() {
        ctx.percepts.timeOfDay = 12_000L;
        RunToShelter walk = walking(200);
        ctx.mover.route = new Path(south(1, 90), false); // the first leg, as far as one search goes
        assertEquals(TaskStatus.RUNNING, walk.tick(ctx), "not refused for stopping short");
        assertTrue(walk.judged());
        ctx.percepts.timeOfDay = 13_200L; // 129.6 now: 90 walked out and 110 on is too far
        RunToShelter late = walking(200);
        ctx.mover.route = new Path(south(1, 90), false);
        assertEquals(TaskStatus.FAILED, late.tick(ctx), "the legs after the first counted in a straight line");
        ctx.percepts.timeOfDay = 12_000L;
        Path leg = ctx.mover.route;
        ctx.mover.route = null;
        RunToShelter run = new RunToShelter(0, 64, 200, dev.luizloyola.anima.core.nav.Gait.WALK);
        run.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        ctx.mover.route = leg;
        assertEquals(TaskStatus.FAILED, run.tick(ctx), "a walk not homeward needs the whole way in one");
    }

    @Test
    void atDarkTheWalkLeftIsWeighedAgain() {
        ctx.percepts.timeOfDay = 13_000L;
        RunToShelter walk = walking(120);
        ctx.mover.route = new Path(south(1, 120), true);
        assertEquals(TaskStatus.RUNNING, walk.tick(ctx));
        ctx.percepts.timeOfDay = 13_500L;
        ctx.percepts.position = new Pos(0, 64, 40);
        assertEquals(TaskStatus.FAILED, walk.tick(ctx), "80 left at dark");
        assertEquals(1, ctx.mover.stopCalls, "the legs are stopped");
        assertTrue(said("too far to make it home, digging in: 80 blocks left at dark"));
        assertTrue(ctx.knowledge.isAvoided(PoiKind.SHELTER, new Pos(0, 64, 120), ctx.percepts.time + 1_000),
                "the next root digs rather than set out again");
    }

    @Test
    void nearEnoughAtDarkItPressesOnAndSaysSoOnce() {
        ctx.percepts.timeOfDay = 13_000L;
        RunToShelter walk = walking(120);
        ctx.mover.route = new Path(south(1, 120), true);
        assertEquals(TaskStatus.RUNNING, walk.tick(ctx));
        ctx.percepts.timeOfDay = 13_600L;
        ctx.percepts.position = new Pos(0, 64, 70);
        assertEquals(TaskStatus.RUNNING, walk.tick(ctx));
        assertEquals(TaskStatus.RUNNING, walk.tick(ctx));
        assertTrue(walk.pressedOn());
        assertEquals(1, ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .filter(e -> e.detail().equals("pressing on home in the dark, 50 left")).count());
        ctx.percepts.position = new Pos(5, 64, 75); // off the route: counted from its nearest cell
        assertEquals(TaskStatus.RUNNING, walk.tick(ctx));
    }

    // ── the rig ──────────────────────────────────────────────────────────────────────────────

    private void remember(int d) {
        Pos in = new Pos(0, 64, d);
        Pos door = new Pos(0, 64, d + 1);
        ctx.knowledge.note(new PoiMemory(PoiKind.SHELTER, door.x() + " " + door.y() + " " + door.z(),
                in, Region.of(in).including(door), 2, false, 0L), 16);
    }

    private RunToShelter walkIn() {
        Method walk = new NightShelterStep().methods().stream().filter(m -> m.describe().equals("walk in"))
                .findFirst().orElseThrow();
        assertTrue(walk.applicable(ctx));
        return assertInstanceOf(RunToShelter.class, walk.decompose(ctx).get(0));
    }

    /** A walk home to (0, 64, d), ordered and under way, its route not yet read. */
    private RunToShelter walking(int d) {
        ctx.mover.route = null;
        RunToShelter walk = new RunToShelter(0, 64, d, dev.luizloyola.anima.core.nav.Gait.WALK, true);
        walk.tick(ctx);
        ctx.mover.setState(MoveState.MOVING);
        return walk;
    }

    private boolean said(String line) {
        return ctx.journalService.recent(ctx.journal().id(), Integer.MAX_VALUE).stream()
                .anyMatch(e -> e.detail().equals(line));
    }

    /** Due south along x = 0, z from {@code from} to {@code to}. */
    private static List<Waypoint> south(int from, int to) {
        List<Waypoint> ways = new ArrayList<>();
        for (int z = from; z <= to; z++) {
            ways.add(new Waypoint(0, 64, z, MoveType.WALK));
        }
        return ways;
    }

    /** Ten east, all the way south, ten back west: 140 blocks to the door at z = 120. */
    private static List<Waypoint> detour() {
        List<Waypoint> ways = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            ways.add(new Waypoint(x, 64, 0, MoveType.WALK));
        }
        for (int z = 1; z <= 120; z++) {
            ways.add(new Waypoint(10, 64, z, MoveType.WALK));
        }
        for (int x = 9; x >= 0; x--) {
            ways.add(new Waypoint(x, 64, 120, MoveType.WALK));
        }
        return ways;
    }
}
