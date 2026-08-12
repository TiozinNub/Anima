package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
import dev.luizloyola.anima.core.brain.sense.Setbacks;
import org.junit.jupiter.api.Test;

/**
 * What a remembered setback does to a route — the arithmetic behind "try something else".
 *
 * <p>A surcharge worth about six steps takes a cheap alternative (a step sideways) after one
 * piece of trouble and an expensive one (a real walk round) only after several — which is what
 * the strength counter is for.
 *
 * <p>{@link #oneWedgeIsEnoughToTakeAStepSideways} is also the regression for a subtler thing: a
 * stride does not STAND in the cells it crosses, so a surcharge attached to a cell let a body
 * sail over the doorway that wedged it. Until the search charged for what a stride passes
 * through, this suite passed in full while the route never changed.
 */
class PathfinderSetbackTest {

    private static final Pos NEAR_DOORWAY = new Pos(1, 1, 1);

    /**
     * <pre>
     *   z=0  1 1 1 1     the body starts at x=1
     *   z=1  # 1 1 #     two doorways side by side: x=1 straight ahead, x=2 one step over
     *   z=2  1 1 1 1     the goal is at x=1
     * </pre>
     * Going round costs well under a step — the cheapest possible "something else".
     */
    private static AsciiWorld sidestepAvailable() {
        return AsciiWorld.of(
                "1111",
                "#11#",
                "1111");
    }

    /**
     * <pre>
     *   z=0  1 1 1 1 1 1 1
     *   z=1  # 1 # # # 1 #   the far doorway is four columns over
     *   z=2  1 1 1 1 1 1 1
     * </pre>
     * Going round costs several steps. It has to be genuinely far: a doorway two columns over is
     * already within what a single wedge buys, so a narrower map could not tell one piece of
     * trouble from three.
     */
    private static AsciiWorld realDetourOnly() {
        return AsciiWorld.of(
                "1111111",
                "#1###1#",
                "1111111");
    }

    private static Path through(AsciiWorld world, SetbackField setbacks) {
        return Pathfinder.find(world,
                PathRequest.of(1, 1, 0, 1, 1, 2, TestBodies.BIPED).avoiding(setbacks));
    }

    /**
     * Which doorway a route used, read as whether it ever left the near column. Asking "is the
     * doorway cell among the waypoints" passes for the wrong reason: a stride covers up to three
     * cells, so the straight route's doorway is not a waypoint at all.
     */
    private static boolean tookTheNearDoor(Path path) {
        return path.waypoints().stream().allMatch(w -> w.x() == NEAR_DOORWAY.x());
    }

    /** The record of a place that has done the same thing to this body {@code times} running. */
    private static SetbackField after(int times, Setbacks.Kind kind) {
        Setbacks memory = new Setbacks();
        for (int i = 0; i < times; i++) {
            memory.record(NEAR_DOORWAY, kind, i);
        }
        return memory.field(times);
    }

    @Test
    void withNothingOnTheRecordItTakesTheDoorInFrontOfIt() {
        assertTrue(tookTheNearDoor(through(sidestepAvailable(), SetbackField.NONE)));
        assertTrue(tookTheNearDoor(through(realDetourOnly(), SetbackField.NONE)));
    }

    /**
     * The retry has to be a different question: re-pathing from the same position over an
     * all-but-identical snapshot produced the same route into the same obstruction — three
     * retries were one attempt made three times.
     */
    @Test
    void oneWedgeIsEnoughToTakeAStepSideways() {
        Path path = through(sidestepAvailable(), after(1, Setbacks.Kind.WEDGED));
        assertTrue(path.reachedGoal(), "it still gets there — the other door is open");
        assertFalse(tookTheNearDoor(path), "but not back through the one that beat it");
    }

    /**
     * One piece of trouble is not proof, so it does not buy a long way round. Same terrain as the
     * test below; the only difference is how many times the place has done it.
     */
    @Test
    void oneWedgeIsNotEnoughToBuyARealDetour() {
        assertTrue(tookTheNearDoor(through(realDetourOnly(), after(1, Setbacks.Kind.WEDGED))));
    }

    @Test
    void aPlaceThatKeepsBeatingTheBodyEventuallyBuysTheLongWayRound() {
        Path path = through(realDetourOnly(), after(3, Setbacks.Kind.WEDGED));
        assertTrue(path.reachedGoal());
        assertFalse(tookTheNearDoor(path), "three times running is not bad luck, it is a wall");
    }

    /**
     * A stray says only where the body ENDED up, a guess at where the trouble was — worth
     * stepping around, never worth walking around.
     */
    @Test
    void aStrayIsWorthASidestepAndNoMore() {
        assertFalse(tookTheNearDoor(through(sidestepAvailable(), after(1, Setbacks.Kind.STRAYED))));
        assertTrue(tookTheNearDoor(through(realDetourOnly(), after(3, Setbacks.Kind.STRAYED))));
    }

    /**
     * A grudge is finite, and that is deliberate: when the only way through is the way that beat
     * us, the body pays and goes rather than declaring the world impassable.
     */
    @Test
    void theOnlyWayThroughIsStillTakenNoMatterWhatHappenedThere() {
        AsciiWorld oneDoorway = AsciiWorld.of(
                "1111",
                "#1##",
                "1111");
        Path path = Pathfinder.find(oneDoorway, PathRequest.of(1, 1, 0, 1, 1, 2, TestBodies.BIPED)
                .avoiding(after(4, Setbacks.Kind.WEDGED)));
        assertTrue(path.reachedGoal());
        assertTrue(tookTheNearDoor(path), "a lean, never a wall");
    }

    @Test
    void aFadedSetbackStopsBendingTheRoute() {
        Setbacks memory = new Setbacks();
        memory.record(NEAR_DOORWAY, Setbacks.Kind.WEDGED, 0);
        assertTrue(tookTheNearDoor(
                through(sidestepAvailable(), memory.field(Setbacks.LIFETIME_TICKS))));
    }

    @Test
    void anEmptyMemoryIsBitForBitTheOldSearch() {
        Path plain = Pathfinder.find(realDetourOnly(),
                PathRequest.of(1, 1, 0, 1, 1, 2, TestBodies.BIPED));
        assertTrue(plain.waypoints()
                .equals(through(realDetourOnly(), SetbackField.NONE).waypoints()));
    }
}
