package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GoalCellTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;

    private static int groundY(AsciiWorld world, int y) {
        return GoalCell.groundY(world, 0, y, 0, BODY);
    }

    /**
     * The 2026-09-10 case: a goal beside a young oak, its head cell leaves, over a cave. The old scan
     * went down through the rock to the pocket and the legs called that arriving.
     */
    @Test
    void aGoalWithNoHeadroomIsNotMovedIntoTheCaveUnderIt() {
        AsciiWorld world = AsciiWorld.of("5")
                .fill(0, 6, 0, 0, 6, 0, CellType.GROUND)   // leaves over the goal
                .fill(0, 1, 0, 0, 2, 0, CellType.PASSABLE); // a pocket under the rock
        assertEquals(5, groundY(world, 5));
    }

    @Test
    void anOpenGoalOverACaveStaysOnTheSurface() {
        AsciiWorld world = AsciiWorld.of("5").fill(0, 1, 0, 0, 2, 0, CellType.PASSABLE);
        assertEquals(5, groundY(world, 5));
    }

    /** What a height read or a click on a top face names: the block itself. */
    @Test
    void aGoalInsideTheTopBlockClimbsOntoIt() {
        assertEquals(5, groundY(AsciiWorld.of("5"), 4));
    }

    /** The head cell of a body standing on the floor: that body is there. */
    @Test
    void aGoalOneUpFallsToTheFloor() {
        assertEquals(3, groundY(AsciiWorld.of("3"), 4));
    }

    /**
     * Two up is over the head of a body on the floor: not where it stands (Luiz, 2026-10-02). On
     * run/normal a chest's stand named eight above a pit floor was walked to the floor, the legs
     * called it arriving, and every walk after that "arrived" without a step, 2,129 times a minute.
     */
    @Test
    void aGoalTwoUpOrMoreIsLeftWhereItWasNamed() {
        assertEquals(5, groundY(AsciiWorld.of("3"), 5));
        assertEquals(9, groundY(AsciiWorld.of("1"), 9));
    }

    @Test
    void aSwimmerFloatsUnderAGoalOneAboveTheWaterButNotTwo() {
        assertEquals(0, groundY(AsciiWorld.of("W"), 1));
        assertEquals(2, groundY(AsciiWorld.of("W"), 2));
    }

    /** The loose reading, for a caller that names a point rather than a stand: the floor under it. */
    @Test
    void theFloorUnderAGoalHighInTheAir() {
        assertEquals(1, GoalCell.floorY(AsciiWorld.of("1"), 0, 9, 0, BODY));
        assertEquals(0, GoalCell.floorY(AsciiWorld.of("W"), 0, 6, 0, BODY));
        assertEquals(5, GoalCell.floorY(AsciiWorld.of("5"), 0, 4, 0, BODY), "climbs out as groundY does");
    }

    /**
     * Under a goal left in the air the search has nowhere to go: the walk ends stranded where the
     * body stands rather than arriving there — the Navigator fails a result like this one.
     */
    @Test
    void aBodyInAPitUnderAGoalInTheAirDoesNotArrive() {
        AsciiWorld pit = AsciiWorld.of("999", "919", "999");
        int goalY = GoalCell.groundY(pit, 1, 9, 1, BODY);
        Path path = Pathfinder.find(pit, PathRequest.of(1, 1, 1, 1, goalY, 1, BODY));

        assertFalse(path.reachedGoal(), "standing on the pit floor is not standing in the air "
                + "eight above it");
        assertTrue(path.isEmpty() || (path.last().x() == 1 && path.last().y() == 1
                && path.last().z() == 1), "and nothing to walk: " + path.waypoints());
    }

    /** A slab is its own feet-cell; the old scan pushed a STEP goal down its column. */
    @Test
    void aSlabGoalIsKept() {
        AsciiWorld world = AsciiWorld.of("3").step(0, 3, 0, 0, 3, 0, 0.5);
        assertEquals(3, groundY(world, 3));
    }

    /** Named deep in a trunk: climbing out would send the walk up the tree. */
    @Test
    void aGoalDeepInSolidIsLeftWhereItWasNamed() {
        AsciiWorld world = AsciiWorld.of("1").fill(0, 1, 0, 0, 8, 0, CellType.GROUND);
        assertEquals(3, groundY(world, 3));
    }

    @Test
    void aSwimmerFloatsAtTheSurfaceOfOpenWater() {
        assertEquals(0, groundY(AsciiWorld.of("W"), 0));
    }

    /** Nobody can stand on a rung: a goal named on a ladder ends on the floor it stands on. */
    @Test
    void aGoalOnALadderEndsAtItsFoot() {
        AsciiWorld world = AsciiWorld.of("1").climb(0, 1, 0, 0, 6, 0);
        assertEquals(1, groundY(world, 4));
    }
}
