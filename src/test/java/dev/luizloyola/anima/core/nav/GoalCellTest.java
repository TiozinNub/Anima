package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void aGoalInTheAirFallsToTheFloor() {
        assertEquals(3, groundY(AsciiWorld.of("3"), 7));
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
        assertEquals(0, groundY(AsciiWorld.of("W"), 3));
    }
}
