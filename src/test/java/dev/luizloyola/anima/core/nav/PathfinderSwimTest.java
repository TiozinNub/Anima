package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Surface-swimming tests over hand-drawn {@link AsciiWorld} terrains. The {@code 'W'} char draws a
 * water column: {@link CellType#WATER} at feet level {@code y=0}, {@link CellType#GROUND} bed below,
 * air above — so the water surface sits one cell below the {@code y=1} feet of an adjacent {@code
 * '1'} shore, the real waterline geometry (enter steps down one, exit climbs up one).
 */
class PathfinderSwimTest {

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return find(world, sx, sy, sz, gx, gy, gz, TestBodies.BIPED);
    }

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz,
                             MoveCapabilities profile) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, profile));
    }

    private static boolean hasMove(Path path, MoveType move) {
        return path.waypoints().stream().anyMatch(w -> w.move() == move);
    }

    /** A river four cells wide — wider than a Person's 3-block leap, so it must be swum. */
    private static final String[] WIDE_RIVER = {"11WWWW11"};

    @Test
    void swimsAcrossWaterTooWideToLeap() {
        Path path = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0);
        assertTrue(path.reachedGoal(), "should reach the far bank by swimming");
        assertEquals(new Waypoint(7, 1, 0, MoveType.WALK), path.last());
        assertTrue(hasMove(path, MoveType.SWIM), "the crossing must use SWIM steps");
        assertTrue(path.waypoints().stream()
                        .filter(w -> w.move() == MoveType.SWIM)
                        .allMatch(w -> w.y() == 0),
                "swim waypoints sit at the water surface, not the bed: " + path.waypoints());
    }

    @Test
    void climbsOutOntoTheFarBank() {
        Path path = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0);
        // The only land adjacent to the water is the far bank at x=6, one cell up out of the
        // surface at y=0.
        assertTrue(path.waypoints().stream()
                        .anyMatch(w -> w.x() == 6 && w.y() == 1 && w.move() == MoveType.JUMP),
                "should climb out onto the far bank at (6,1) as a JUMP: " + path.waypoints());
    }

    @Test
    void aLandWalkerCannotCrossOpenWater() {
        MoveCapabilities landOnly = new MoveCapabilities(2, 1, 3, 3, false);
        Path walker = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0, landOnly);
        assertFalse(walker.reachedGoal(), "a non-swimmer can't cross a 4-wide river");
        assertFalse(hasMove(walker, MoveType.SWIM), "a non-swimmer never produces a SWIM step");

        // Same terrain, same goal: the only difference is the capability.
        Path swimmer = find(AsciiWorld.of(WIDE_RIVER), 0, 1, 0, 7, 1, 0);
        assertTrue(swimmer.reachedGoal());
    }

    @Test
    void stillLeapsNarrowWaterRatherThanSwim() {
        // A one-wide water gap: a single leap (2.4) beats entering-crossing-exiting (~5.0), so the
        // search keeps its feet dry.
        Path path = find(AsciiWorld.of("11W11"), 0, 1, 0, 4, 1, 0);
        assertTrue(path.reachedGoal());
        assertTrue(hasMove(path, MoveType.LEAP), "narrow water should be leapt: " + path.waypoints());
        assertFalse(hasMove(path, MoveType.SWIM), "narrow water should not be swum: " + path.waypoints());
    }

    @Test
    void prefersAShortDryDetourOverSwimming() {
        // The far dry row (z=2) does not border the water, so the go-around pays no careful
        // penalty and stays cheaper than the long wet crossing.
        AsciiWorld world = AsciiWorld.of(
                "1WWWW1",
                "111111",
                "111111");
        Path path = find(world, 0, 1, 0, 5, 1, 0);
        assertTrue(path.reachedGoal());
        assertFalse(hasMove(path, MoveType.SWIM),
                "a cheap dry detour should win over swimming: " + path.waypoints());
    }

    // --- plunges: falling into water, which maxDrop has no say over --------------------------

    /**
     * An 8-high bank over a pool — past the body's {@code maxDrop} of 3, so only the water makes
     * it a route.
     */
    private static final String[] CLIFF_OVER_POOL = {"8W1"};

    @Test
    void plungesFromAHeightNoDropWouldSurvive() {
        Path path = find(AsciiWorld.of(CLIFF_OVER_POOL), 0, 8, 0, 2, 1, 0);
        assertTrue(path.reachedGoal(), "should jump in and swim out: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .anyMatch(w -> w.x() == 1 && w.y() == 0 && w.move() == MoveType.SWIM),
                "the entry should be a SWIM landing at the waterline: " + path.waypoints());
    }

    @Test
    void aLandWalkerStillWillNotJumpIn() {
        MoveCapabilities landOnly = new MoveCapabilities(1.8, 1, 3, 3, false);
        Path path = find(AsciiWorld.of(CLIFF_OVER_POOL), 0, 8, 0, 2, 1, 0, landOnly);
        assertFalse(path.reachedGoal(), "water is impassable to a non-swimmer, however deep");
    }

    /**
     * The same cliff with a lid over the pool: the fall column is what the probe scans, so a body
     * cannot plunge through a ceiling into water it can see under one.
     */
    @Test
    void willNotPlungeThroughAnObstructedColumn() {
        AsciiWorld world = AsciiWorld.of(CLIFF_OVER_POOL)
                .fill(1, 4, 0, 1, 4, 0, CellType.OBSTACLE);
        Path path = find(world, 0, 8, 0, 2, 1, 0);
        assertFalse(path.reachedGoal(), "a blocked column is not a plunge: " + path.waypoints());
    }

    /**
     * The plunge's one hard edge, pinned from both sides: a tower exactly as high as the search
     * bound is dived off, one higher is not — so this test notices the bound being removed or
     * quietly retuned.
     *
     * <p>Built with {@code fill} because the heightmap only draws 1..9.
     */
    private static Path plungeFromTowerOfHeight(int height) {
        AsciiWorld world = AsciiWorld.of("1W1")
                .fill(0, -1, 0, 0, height - 1, 0, CellType.GROUND);
        return find(world, 0, height, 0, 2, 1, 0);
    }

    @Test
    void plungesFromExactlyTheSearchBound() {
        Path path = plungeFromTowerOfHeight(32);
        assertTrue(path.reachedGoal(), "a 32-block plunge is in reach: " + path.waypoints());
        assertTrue(hasMove(path, MoveType.SWIM));
    }

    @Test
    void refusesAPlungeBeyondTheSearchBound() {
        Path path = plungeFromTowerOfHeight(33);
        assertFalse(path.reachedGoal(), "33 is past the bound: " + path.waypoints());
    }
}
