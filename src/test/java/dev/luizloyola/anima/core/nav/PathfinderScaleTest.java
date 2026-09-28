package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Scaling a soft step (docs/superpowers/specs/2026-09-28-bridging-design.md): the lip cut, a jump
 * into the notch, the lip laid back underfoot. Two or three up, soft ground only, nothing wet or
 * harmful beside the cut and nothing on the lip, and only for a walk allowed it.
 */
class PathfinderScaleTest {

    private static final MoveCapabilities SCALER = TestBodies.BIPED.withScaling(true);

    /** A step of {@code height} across three rows, its ground soft unless said otherwise. */
    private static AsciiWorld step(int height) {
        String row = "111" + String.valueOf(1 + height).repeat(3);
        return AsciiWorld.of(row, row, row).soft(3, 0, 0, 5, height, 2);
    }

    private static Path find(NavGrid grid, int gx, int gy, MoveCapabilities body) {
        return Pathfinder.find(grid, PathRequest.of(1, 1, 1, gx, gy, 1, body));
    }

    private static long scales(Path path) {
        return path.waypoints().stream().filter(w -> w.move() == MoveType.SCALE).count();
    }

    @Test
    void aTwoHighSoftStepIsScaled() {
        NavGrid grid = step(2).bounded();
        assertFalse(find(grid, 4, 3, TestBodies.BIPED).reachedGoal(), "nobody jumps two");
        Path path = find(grid, 4, 3, SCALER);
        assertTrue(path.reachedGoal());
        assertEquals(1, scales(path));
        assertEquals(0, path.laid(), "a scale spends nothing from the pocket");
        Waypoint previous = new Waypoint(1, 1, 1, MoveType.WALK);
        for (Waypoint to : path.waypoints()) {
            for (CellNeed need : PathIntegrity.edgeNeeds(previous, to, SCALER)) {
                assertTrue(NavGrids.satisfies(grid, need), "the leg into " + to + " needs " + need);
            }
            previous = to;
        }
    }

    @Test
    void aThreeHighStepTakesTwoCuts() {
        Path path = find(step(3).bounded(), 4, 4, SCALER);
        assertTrue(path.reachedGoal());
        Waypoint scale = path.waypoints().stream().filter(w -> w.move() == MoveType.SCALE)
                .findFirst().orElseThrow();
        assertEquals(4, scale.y(), "three up in one move");
    }

    @Test
    void fourUpIsACliffNotAStep() {
        assertFalse(find(step(4).bounded(), 4, 5, SCALER).reachedGoal());
    }

    @Test
    void stoneIsNotScaled() {
        String row = "111333";
        assertFalse(find(AsciiWorld.of(row, row, row).bounded(), 4, 3, SCALER).reachedGoal());
    }

    @Test
    void aLipWithWaterBehindItIsNotCut() {
        AsciiWorld pond = step(2).fill(4, 2, 0, 5, 2, 2, CellType.WATER);
        assertFalse(find(pond.bounded(), 3, 3, SCALER).reachedGoal(),
                "cutting the lip would let the pond into the notch");
    }

    @Test
    void somethingOnTheLipIsNotBrokenOffIt() {
        AsciiWorld carpeted = step(2).step(3, 3, 0, 3, 3, 2, 0.0625);
        assertFalse(find(carpeted.bounded(), 4, 3, SCALER).reachedGoal());
    }

    @Test
    void noScaleWithoutHeadroomToJump() {
        AsciiWorld lidded = step(2).fill(0, 3, 0, 2, 3, 2, CellType.GROUND);
        assertFalse(find(lidded.bounded(), 4, 3, SCALER).reachedGoal());
    }

    /** A scale is an ordinary move: the first search finds it, with nothing in the pocket. */
    @Test
    void aScaleNeedsNoBlocksAndNoSecondSearch() {
        Path path = find(step(2).bounded(), 4, 3, SCALER);
        assertTrue(path.reachedGoal());
        assertEquals(0, SCALER.maxLaid());
    }
}
