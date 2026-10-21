package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Holes;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Openness;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pull to be under cover at night (Luiz, 2026-10-20): nothing by day, rising through dusk,
 * full from nightfall to sunrise, waiting where it stands when it has nowhere to go.
 *
 * <p>The body stands at (0, 64, 0); the shelter it may remember has its door at (10, 64, -1) and
 * the cell inside it at (10, 64, 0).
 */
class NightShelterInstinctTest {

    private static final double FULL = 0.7;
    private static final long MIDNIGHT = 18_000L;
    private static final Pos DOOR = new Pos(10, 64, -1);
    private static final Pos IN = new Pos(10, 64, 0);

    private final FakeContext ctx = new FakeContext();
    private final NightShelterInstinct night = new NightShelterInstinct();

    @BeforeEach
    void aBodyThatMindsTheDark() {
        ctx.profile = TestSpecies.with(ProfileAspect.NIGHT_SHELTER_PRESSURE, FULL);
        ctx.percepts.position = new Pos(0, 64, 0);
        ctx.percepts.timeOfDay = MIDNIGHT;
    }

    @Test
    void thePullIsNothingByDayRisesThroughDuskAndHoldsUntilSunrise() {
        assertEquals(0.0, NightShelterInstinct.pull(6_000L, FULL));
        assertEquals(0.0, NightShelterInstinct.pull(11_999L, FULL));
        assertEquals(FULL / 2, NightShelterInstinct.pull(12_750L, FULL), 1e-9, "halfway through dusk");
        assertEquals(FULL, NightShelterInstinct.pull(13_500L, FULL), "nightfall");
        assertEquals(FULL, NightShelterInstinct.pull(23_999L, FULL), "still dark enough to spawn in");
        assertEquals(0.0, NightShelterInstinct.pull(24_000L, FULL), "sunrise");
    }

    @Test
    void withNoShelterInMindItWaitsTheNightOutRatherThanWork() {
        assertEquals(FULL, night.pressure(ctx), "a settler whose dig failed went exploring at night");
        TaskExecutor executor = new TaskExecutor();
        executor.run(night.root(ctx), ctx);
        executor.tick(ctx);
        assertEquals(0, ctx.mover.moveToCalls, "it stays where it is");
        assertTrue(executor.isBusy(), "holding");
    }

    @Test
    void aRememberedShelterInReachPullsAtNightAndTheBodyWalksIn() {
        remember();
        assertEquals(FULL, night.pressure(ctx));
        TaskExecutor executor = new TaskExecutor();
        executor.run(night.root(ctx), ctx);
        executor.tick(ctx);
        assertEquals(List.of(IN.x(), IN.y(), IN.z()),
                List.of(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ));
    }

    @Test
    void offByDefaultAndOffByDay() {
        remember();
        ctx.percepts.timeOfDay = 6_000L;
        assertEquals(0.0, night.pressure(ctx));
        ctx.percepts.timeOfDay = MIDNIGHT;
        ctx.profile = TestSpecies.PROFILE;
        assertEquals(0.0, night.pressure(ctx), "a body that never minds the dark");
        ctx.profile = TestSpecies.with(ProfileAspect.NIGHT_SHELTER_PRESSURE, FULL);
        ctx.percepts.timeOfDay = null;
        assertEquals(0.0, night.pressure(ctx), "nowhere the sun keeps hours");
    }

    @Test
    void aShelterOutOfReachIsNotWalkedTo() {
        ctx.knowledge.note(new PoiMemory(PoiKind.SHELTER, "100 64 -1", new Pos(100, 64, 0),
                Region.of(new Pos(100, 64, 0)), 2, false, 0L), 16);
        assertFalse(method("walk in").applicable(ctx));
    }

    @Test
    void insideAShelterAtNightItHoldsAndSunriseLetsItGo() {
        ctx.percepts.enclosure = new Enclosure(Openness.OPENABLE, Holes.NONE, true,
                ctx.percepts.position, Set.of(ctx.percepts.position), List.of(), List.of(), 1L);
        assertEquals(FULL, night.pressure(ctx));
        List<Task> plan = method("stay in").decompose(ctx);
        assertInstanceOf(Idle.class, plan.get(0));
        ctx.percepts.timeOfDay = 0L;
        assertEquals(0.0, night.pressure(ctx), "sunrise");
    }

    @Test
    void anOpenDoorIsShutWithNothingComing() {
        Pos door = new Pos(1, 64, 0);
        ctx.percepts.enclosure = new Enclosure(Openness.CLOSEABLE, Holes.NONE, true,
                ctx.percepts.position, Set.of(ctx.percepts.position), List.of(door),
                List.of(door), 1L);
        assertEquals(FULL, night.pressure(ctx));
        Method shut = method("shut the doors");
        assertTrue(shut.applicable(ctx));
        assertTrue(shut.decompose(ctx).stream().anyMatch(ShutDoor.class::isInstance));
    }

    @Test
    void aWayInThatFailsLeavesThatShelterAloneAWhile() {
        remember();
        NightShelterStep root = (NightShelterStep) night.root(ctx);
        Method giveUp = root.methods().stream().filter(m -> m.describe().equals("no way in")).findFirst()
                .orElseThrow();
        assertFalse(giveUp.applicable(ctx), "nothing tried yet");
        root.methods().stream().filter(m -> m.describe().equals("walk in")).findFirst()
                .orElseThrow().decompose(ctx);
        assertTrue(giveUp.applicable(ctx), "the walk in was set out on, and fell through");
        for (Task task : giveUp.decompose(ctx)) {
            ((PrimitiveTask) task).tick(ctx);
        }
        assertTrue(ctx.knowledge.isAvoided(PoiKind.SHELTER, IN, ctx.percepts.time));
        assertFalse(method("walk in").applicable(ctx), "the next root does not ask it again");
    }

    @Test
    void somethingAboutOnThisSideOutweighsTheNightAndNothingShutOutDoes() {
        ctx.percepts.beings = List.of(FakePercepts.monsterAt(new Pos(2, 64, 0), 2.0, true));
        assertEquals(0.0, night.pressure(ctx), "waiting in the open is no cover: spiders hit her eight times");
        ctx.percepts.enclosure = new Enclosure(Openness.OPENABLE, Holes.NONE, true,
                ctx.percepts.position, Set.of(ctx.percepts.position), List.of(), List.of(), 1L);
        assertEquals(FULL, night.pressure(ctx), "behind a shut door it is shut out, and the night holds");
    }

    private void remember() {
        ctx.knowledge.note(new PoiMemory(PoiKind.SHELTER, DOOR.x() + " " + DOOR.y() + " " + DOOR.z(),
                IN, Region.of(IN).including(DOOR), 2, false, 0L), 16);
    }

    private static Method method(String name) {
        return new NightShelterStep().methods().stream().filter(m -> m.describe().equals(name))
                .findFirst().orElseThrow();
    }
}
