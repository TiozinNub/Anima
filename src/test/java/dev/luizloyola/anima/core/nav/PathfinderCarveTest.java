package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Carving a two-block step (docs/superpowers/specs/2026-09-28-bridging-design.md): the lip cut and
 * kept, the step one block for good — only where the notch would look natural. Everywhere else the
 * step is scaled and the world left as it was.
 */
class PathfinderCarveTest {

    private static final MoveCapabilities SCALER = TestBodies.BIPED.withScaling(true);

    /** A two-block step across three rows onto flat ground, soft, grass on top. */
    private static AsciiWorld grassStep() {
        String row = "111333";
        return AsciiWorld.of(row, row, row).soft(3, 0, 0, 5, 1, 2).regrows(3, 2, 0, 5, 2, 2);
    }

    private static Path find(NavGrid grid, int gx, int gy) {
        return Pathfinder.find(grid, PathRequest.of(1, 1, 1, gx, gy, 1, SCALER));
    }

    private static long count(Path path, MoveType move) {
        return path.waypoints().stream().filter(w -> w.move() == move).count();
    }

    private static void assertWalkable(NavGrid grid, Path path) {
        Waypoint previous = new Waypoint(1, 1, 1, MoveType.WALK);
        for (Waypoint to : path.waypoints()) {
            for (CellNeed need : PathIntegrity.edgeNeeds(previous, to, SCALER)) {
                assertTrue(NavGrids.satisfies(grid, need), "the leg into " + to + " needs " + need);
            }
            previous = to;
        }
    }

    @Test
    void aGrassStepOntoFlatGroundIsCarved() {
        NavGrid grid = grassStep().bounded();
        Path path = find(grid, 5, 3);
        assertTrue(path.reachedGoal());
        assertEquals(1, count(path, MoveType.CARVE), () -> "carved, not scaled: " + path.waypoints());
        assertEquals(0, count(path, MoveType.SCALE));
        Waypoint carve = path.waypoints().stream().filter(w -> w.move() == MoveType.CARVE)
                .findFirst().orElseThrow();
        assertEquals(2, carve.y(), "into the notch, one up");
        assertWalkable(grid, path);
    }

    @Test
    void dirtThatStaysBareIsScaledInstead() {
        String row = "111333";
        NavGrid grid = AsciiWorld.of(row, row, row).soft(3, 0, 0, 5, 2, 2).bounded();
        Path path = find(grid, 5, 3);
        assertEquals(0, count(path, MoveType.CARVE));
        assertEquals(1, count(path, MoveType.SCALE));
    }

    /**
     * A staircase of two-block steps: a lip with the next step two above it is scaled — carved, the
     * next body would meet three. The top lip, with flat ground behind it, is carved.
     */
    @Test
    void aLipBelowTheNextStepIsScaledAndTheTopOneCarved() {
        String row = "1135555";
        NavGrid grid = AsciiWorld.of(row, row, row).soft(2, 0, 0, 6, 3, 2)
                .regrows(2, 2, 0, 2, 2, 2).regrows(3, 4, 0, 6, 4, 2).bounded();
        Path path = find(grid, 5, 5);
        assertTrue(path.reachedGoal());
        for (Waypoint w : path.waypoints()) {
            if (w.x() == 2 && w.move() == MoveType.CARVE) {
                throw new AssertionError("carved under the next step: " + path.waypoints());
            }
        }
        assertEquals(1, count(path, MoveType.CARVE), () -> "the top lip is carved: " + path.waypoints());
        assertWalkable(grid, path);
    }

    @Test
    void aFlowerOnTheLipIsScaledRound() {
        NavGrid grid = grassStep().fixed(3, 3, 0, 3, 3, 2).bounded();
        Path path = find(grid, 5, 3);
        assertEquals(0, count(path, MoveType.CARVE), () -> "the flower is lost: " + path.waypoints());
    }

    @Test
    void aNotchOverAHollowIsNotCarved() {
        NavGrid grid = grassStep().fill(3, 0, 0, 3, 0, 2, CellType.PASSABLE).bounded();
        Path path = find(grid, 5, 3);
        assertEquals(0, count(path, MoveType.CARVE), () -> "the notch floor is a crust: " + path.waypoints());
        assertTrue(path.reachedGoal(), "scaled instead");
    }

    @Test
    void aWalkThatMayNotScaleNeverCarves() {
        assertFalse(Pathfinder.find(grassStep().bounded(),
                PathRequest.of(1, 1, 1, 5, 3, 1, TestBodies.BIPED)).reachedGoal());
    }

    @Test
    void aCarveNeedsNoBlocks() {
        Path path = find(grassStep().bounded(), 5, 3);
        assertEquals(0, path.laid());
    }
}
