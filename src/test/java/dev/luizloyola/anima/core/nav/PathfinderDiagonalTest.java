package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Diagonal steps: across corners, and up and down.
 *
 * <p>Two rules were dropped as too strict — a diagonal was <em>level-only</em>, and its flanks
 * had to be <em>standable</em>. What survives: a diagonal may not cut through a solid, and may
 * not put the body into something that hurts.
 *
 * <p>Mostly pairs (the same shape with and without the thing that should stop it), because "a
 * path exists" never says which rule allowed it.
 */
class PathfinderDiagonalTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, BODY));
    }

    /** The corner-touching pair: ground at (0,0) and (1,1), nothing at (1,0) or (0,1). */
    private static AsciiWorld cornerPair() {
        return AsciiWorld.of(
                "1 ",
                " 1");
    }

    @Test
    void crossesTwoBlocksThatTouchOnlyAtACorner() {
        assertTrue(find(cornerPair(), 0, 1, 0, 1, 1, 1).reachedGoal(),
                "the feet are carried by the two blocks; the empty corners are passed over");
        assertEquals(1, find(cornerPair(), 0, 1, 0, 1, 1, 1).waypoints().size(),
                "and it is ONE move — going round is not available, there is nothing to go round on");
    }

    @Test
    void stillRefusesToCutThroughASolidCorner() {
        // Same shape, but one flank is a wall the body would clip.
        AsciiWorld walled = AsciiWorld.of(
                "1#",
                " 1");
        assertFalse(find(walled, 0, 1, 0, 1, 1, 1).reachedGoal(),
                "a solid flank is a corner cut, with or without a floor beside it");
    }

    @Test
    void refusesAFlankTheBodyWouldPassThroughAndBurnIn() {
        // At the body's own level, not below it: the flank check is about what the body sweeps
        // through, and what lies under the corner is a matter for the careful throttle.
        AsciiWorld inTheWay = cornerPair().fill(1, 1, 0, 1, 1, 0, CellType.DANGER);
        assertFalse(find(inTheWay, 0, 1, 0, 1, 1, 1).reachedGoal(),
                "the corner is empty enough to walk through and hot enough not to");

        AsciiWorld underneath = cornerPair().fill(1, 0, 0, 1, 0, 0, CellType.DANGER);
        assertTrue(find(underneath, 0, 1, 0, 1, 1, 1).reachedGoal(),
                "lava a level DOWN is crossed — the feet never leave the two blocks");
        assertTrue(NavGrids.isNearDeepDrop(underneath, BODY.maxDrop(), 0, 1, 0),
                "...and it is careful ground, which is what actually keeps her off the edge");
    }

    @Test
    void aCarpetInTheFlankIsSteppedOverNotWalkedAround() {
        // A partial floor low enough to sweep through is no reason to refuse the corner.
        AsciiWorld carpeted = cornerPair().step(1, 1, 0, 1, 1, 0, 0.0625);
        assertTrue(find(carpeted, 0, 1, 0, 1, 1, 1).reachedGoal());
    }

    @Test
    void aSlabStandingInTheFlankIsToleratedButAFullBlockIsNot() {
        // A height test, not a solid/not-solid one: half a block is inside the step height, a
        // whole one is the wall the corner-cut rule is about.
        AsciiWorld slab = cornerPair().step(1, 1, 0, 1, 1, 0, 0.5);
        // Two tall on purpose: a single block in the flank is a STEPPING STONE — jump on, drop
        // off the far side, and the goal is reached with no diagonal involved at all.
        AsciiWorld wall = cornerPair().fill(1, 1, 0, 1, 2, 0, CellType.GROUND);
        assertTrue(find(slab, 0, 1, 0, 1, 1, 1).reachedGoal());
        assertFalse(find(wall, 0, 1, 0, 1, 1, 1).reachedGoal());
    }

    @Test
    void climbsAndDescendsADiagonalStaircase() {
        // Each step is a full block up on the diagonal — the B3/B4 shape. Going up is a jump,
        // coming down is a drop, and neither existed while diagonals were level-only.
        AsciiWorld up = AsciiWorld.of("1234");
        Path climbed = find(up, 0, 1, 0, 3, 4, 0);
        assertTrue(climbed.reachedGoal(), "a staircase does not stop being one when it turns");

        AsciiWorld down = AsciiWorld.of("4321");
        assertTrue(find(down, 0, 4, 0, 3, 1, 0).reachedGoal());
    }

    @Test
    void takesADiagonalDropWhenTheCardinalWayDownIsVoid() {
        // H7's shape: the only route down is across the corner. Both cardinal neighbours are
        // bottomless, so a route existing at all means the diagonal drop exists.
        AsciiWorld world = AsciiWorld.of(
                "4 ",
                " 1");
        Path path = find(world, 0, 4, 0, 1, 1, 1);
        assertTrue(path.reachedGoal(), "a drop is still a drop when it goes sideways");
        assertEquals(MoveType.DROP, path.last().move());
    }

    @Test
    void refusesADiagonalDropDeeperThanTheBodyAccepts() {
        // maxDrop is 3. Four down on the diagonal is a hole, exactly as it is straight ahead.
        AsciiWorld four = AsciiWorld.of(
                "5 ",
                " 1");
        AsciiWorld three = AsciiWorld.of(
                "4 ",
                " 1");
        assertFalse(find(four, 0, 5, 0, 1, 1, 1).reachedGoal(), "4 down is past maxDrop");
        assertTrue(find(three, 0, 4, 0, 1, 1, 1).reachedGoal(), "3 down is not");
    }

    @Test
    void refusesADiagonalRiseHigherThanAJumpReaches() {
        AsciiWorld one = AsciiWorld.of(
                "1 ",
                " 2");
        AsciiWorld two = AsciiWorld.of(
                "1 ",
                " 3");
        assertTrue(find(one, 0, 1, 0, 1, 2, 1).reachedGoal(), "one block up is a jump");
        assertFalse(find(two, 0, 1, 0, 1, 3, 1).reachedGoal(), "two is a wall, diagonal or not");
    }

    @Test
    void aDiagonalDoglegOverVoidIsWalkedEndToEnd() {
        // C7's shape as a map: a staircase of single blocks stepping sideways, nothing beside any
        // of them. Every move in it is a corner diagonal.
        AsciiWorld dogleg = AsciiWorld.of(
                "1    ",
                " 1   ",
                "  1  ",
                "   1 ",
                "    1");
        Path path = find(dogleg, 0, 1, 0, 4, 1, 4);
        assertTrue(path.reachedGoal(), "a chain of corners is still a route");
        assertEquals(4, path.waypoints().size(), "and it is four diagonal steps, not a detour");
    }
}
