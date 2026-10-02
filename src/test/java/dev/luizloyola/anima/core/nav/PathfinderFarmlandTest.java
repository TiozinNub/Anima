package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.task.Standing;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Farmland is kept off unless the walk allows it (Luiz, 2026-10-01): a body landing on it tramples
 * the crop, and the walk-over pickup takes what pops. Drawn as vanilla lays it — the field's own
 * cell is the floor, 15/16 high, where the ground block would be.
 */
class PathfinderFarmlandTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;
    private static final MoveCapabilities FARMER = BODY.withFarmland(true);

    private static Path find(NavGrid world, MoveCapabilities body, int sx, int sy, int sz,
                             int gx, int gy, int gz) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, body));
    }

    private static List<Waypoint> onFarmland(NavGrid world, Path path) {
        return path.waypoints().stream().filter(w -> world.farmland(w.x(), w.y(), w.z())).toList();
    }

    private static AsciiWorld flat(int width, int depth) {
        String[] rows = new String[depth];
        java.util.Arrays.fill(rows, "1".repeat(width));
        return AsciiWorld.of(rows);
    }

    @Test
    void aRouteGoesRoundAField() {
        // Rows z 1..4 are field from x 2 to 6; only z 0 is a way past.
        AsciiWorld world = flat(9, 5).farmland(2, 0, 1, 6, 0, 4);

        Path path = find(world, BODY, 0, 1, 2, 8, 1, 2);
        assertTrue(path.reachedGoal());
        assertEquals(List.of(), onFarmland(world, path), "not one step on the field");
        assertTrue(path.waypoints().stream().anyMatch(w -> w.z() == 0), "round by the one free row");

        assertFalse(onFarmland(world, find(world, FARMER, 0, 1, 2, 8, 1, 2)).isEmpty(),
                "allowed onto it, the same walk crosses: the field was what it went round");
    }

    @Test
    void aGoalAcrossAFieldWithNoOtherWayIsRefusedButNotSealed() {
        // Five wide, past a 3-leap; the map's edges are walls.
        AsciiWorld world = flat(13, 3).farmland(4, 0, 0, 8, 0, 2);

        Path path = find(world, BODY, 1, 1, 1, 11, 1, 1);
        assertFalse(path.reachedGoal());
        assertEquals(List.of(), onFarmland(world, path));
        assertFalse(path.sealed(), "a field is a rule, not a wall: nothing to dig out of");

        AsciiWorld walled = flat(13, 3).fill(4, 0, 0, 8, 3, 2, CellType.OBSTACLE);
        assertTrue(find(walled, BODY, 1, 1, 1, 11, 1, 1).sealed(),
                "the same pen walled is sealed — so the field is what cleared it");
    }

    @Test
    void theSameGoalIsReachedWhenTheWalkAllowsFarmland() {
        AsciiWorld world = flat(13, 3).farmland(4, 0, 0, 8, 0, 2);

        Path path = find(world, FARMER, 1, 1, 1, 11, 1, 1);
        assertTrue(path.reachedGoal());
        assertFalse(onFarmland(world, path).isEmpty());
    }

    @Test
    void aBodyStandingOnFarmlandWalksOffByTheNearestEdge() {
        // A field x 0..8 between two free rows; the goal is east, past the field's end.
        AsciiWorld world = flat(13, 5).farmland(0, 0, 1, 8, 0, 3);

        Path path = find(world, BODY, 1, 0, 2, 12, 1, 2);
        assertTrue(path.reachedGoal(), "the cell it stands in must not strand it");
        assertEquals(1, onFarmland(world, path).size(),
                "one step to the edge, not eight across the crop toward the goal");
        assertTrue(world.farmland(path.waypoints().get(0).x(), path.waypoints().get(0).y(),
                path.waypoints().get(0).z()), "and that step first");
    }

    @Test
    void aBodyThatLeftItsFieldDoesNotCrossTheNext() {
        // Its own field x 0..2, free ground 3..5, a second field 6..10 across the map, then the goal.
        AsciiWorld world = flat(16, 3).farmland(0, 0, 0, 2, 0, 2).farmland(6, 0, 0, 10, 0, 2);

        Path path = find(world, BODY, 1, 0, 1, 14, 1, 1);
        assertFalse(path.reachedGoal());
        assertTrue(path.waypoints().stream().noneMatch(w -> w.x() >= 6 && w.x() <= 10));
    }

    @Test
    void aTrampledCellInAFieldIsWalkedOff() {
        // Dirt at (4, 0, 1), farmland on all four sides of it and across the map.
        AsciiWorld world = flat(13, 3)
                .farmland(0, 0, 0, 8, 0, 0)
                .farmland(0, 0, 2, 8, 0, 2)
                .farmland(0, 0, 1, 3, 0, 1)
                .farmland(5, 0, 1, 8, 0, 1);

        assertTrue(find(world, BODY, 4, 1, 1, 12, 1, 1).reachedGoal());
    }

    @Test
    void noDeckIsLaidOnFarmland() {
        // A sunken field two under the walkway's feet, four wide: a deck over it would till it to
        // dirt. A body that drops one only bridges a gap whose floor it cannot reach — a deeper
        // dropper's scan stops on the field, refused — and bounded, no pillar leans on the edge.
        MoveCapabilities builder = new MoveCapabilities(1.8, 1, 1, 3, true, 36, true, true)
                .withLaid(16);
        NavGrid world = AsciiWorld.of("2221111222", "2221111222", "2221111222")
                .farmland(3, 0, 0, 6, 0, 2).bounded();

        Path path = find(world, builder, 1, 2, 1, 8, 2, 1);
        assertFalse(path.reachedGoal(), path.waypoints().toString());

        NavGrid ravine = AsciiWorld.of("222    222", "222    222", "222    222").bounded();
        assertTrue(find(ravine, builder, 1, 2, 1, 8, 2, 1).reachedGoal(),
                "the same span over nothing is decked: the field is what stopped it");
    }

    @Test
    void farmlandIsNoStandingSpotUnlessAllowed() {
        AsciiWorld world = flat(3, 1).farmland(1, 0, 0, 1, 0, 0);

        assertFalse(Pathfinder.standable(world, BODY, 1, 0, 0));
        assertTrue(Pathfinder.standable(world, FARMER, 1, 0, 0));
        assertFalse(Standing.standable(world, BODY, 1, 0, 0), "a wander never picks it");
        assertTrue(Standing.standable(world, FARMER, 1, 0, 0));
        assertEquals("farmland underfoot", Standing.whyNot(world, BODY, 1, 0, 0));
    }
}
