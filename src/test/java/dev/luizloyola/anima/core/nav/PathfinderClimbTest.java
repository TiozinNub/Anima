package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ladders and vines — {@link CellType#CLIMB}. Until 2026-09-26 a ladder read as a wall and vines
 * as air, so a shaft was a hole and a ladder up a wall was the wall.
 */
class PathfinderClimbTest {

    private static final MoveCapabilities CLIMBER = TestBodies.BIPED;
    private static final MoveCapabilities NO_CLIMB = new MoveCapabilities(1.8, 1, 3, 3, true, 36, true, false);

    /** A wall six high at {@code x = 4}, with a ladder up its face at {@code x = 3}. */
    private static AsciiWorld ladderUpAWall() {
        return AsciiWorld.of("1111777").climb(3, 1, 0, 3, 6, 0);
    }

    /** A pit six deep between two banks, a ladder down each side of it. */
    private static AsciiWorld pitWithLadders() {
        return AsciiWorld.of("77711111777").climb(3, 1, 0, 3, 6, 0).climb(7, 1, 0, 7, 6, 0);
    }

    private static Path find(NavGrid world, MoveCapabilities body,
                             int sx, int sy, int gx, int gy) {
        Path path = Pathfinder.find(world, PathRequest.of(sx, sy, 0, gx, gy, 0, body));
        Waypoint from = new Waypoint(sx, sy, 0, MoveType.WALK);
        for (Waypoint to : path.waypoints()) {
            for (CellNeed need : PathIntegrity.edgeNeeds(from, to, body)) {
                assertTrue(NavGrids.satisfies(world, need), need + " on the edge into " + to);
            }
            from = to;
        }
        return path;
    }

    private static List<MoveType> moves(Path path) {
        return path.waypoints().stream().map(Waypoint::move).toList();
    }

    @Test
    void aLadderUpAWallIsClimbedAndSteppedOffOntoTheTop() {
        Path path = find(ladderUpAWall(), CLIMBER, 0, 1, 6, 7);
        assertTrue(path.reachedGoal());
        List<Waypoint> ways = path.waypoints();
        int top = ways.indexOf(new Waypoint(4, 7, 0, MoveType.CLIMB));
        assertTrue(top > 0, "stepped off the top rung onto the wall as a climb, not a jump: " + ways);
        assertEquals(new Waypoint(3, 6, 0, MoveType.CLIMB), ways.get(top - 1));
        assertFalse(moves(path).contains(MoveType.JUMP));
    }

    @Test
    void aBodyThatCannotClimbFindsTheWallAWall() {
        assertFalse(find(ladderUpAWall(), NO_CLIMB, 0, 1, 6, 7).reachedGoal());
    }

    @Test
    void theSameWallWithNoLadderIsAWallToAClimberToo() {
        assertFalse(find(AsciiWorld.of("1111777"), CLIMBER, 0, 1, 6, 7).reachedGoal(),
                "the ladder is what made it passable");
    }

    @Test
    void aPitIsClimbedDownOneSideAndUpTheOther() {
        Path path = find(pitWithLadders(), CLIMBER, 0, 7, 10, 7);
        assertTrue(path.reachedGoal());
        // Stepping off the rim into the shaft is caught by the ladder, a rung under the rim.
        assertTrue(path.waypoints().contains(new Waypoint(3, 6, 0, MoveType.CLIMB)));
        assertTrue(path.waypoints().contains(new Waypoint(8, 7, 0, MoveType.CLIMB)),
                "and off the far ladder onto the rim");
        // It may let go a little above the floor — a drop it would take anywhere — but it climbs
        // most of the way: six blocks is past this body's drop.
        Waypoint from = new Waypoint(0, 7, 0, MoveType.WALK);
        for (Waypoint to : path.waypoints()) {
            assertTrue(from.y() - to.y() <= BODY_DROP, "fell " + (from.y() - to.y()) + " to " + to);
            from = to;
        }
    }

    private static final int BODY_DROP = 3;

    /** Whether a body at this feet-cell hangs on the climbable rather than standing. */
    private static boolean hanging(NavGrid world, Waypoint at) {
        return world.cell(at.x(), at.y(), at.z()) == CellType.CLIMB
                && world.cell(at.x(), at.y() - 1, at.z()) != CellType.GROUND;
    }

    @Test
    void aBodyOnTheLadderCanGoEitherWay() {
        assertTrue(find(ladderUpAWall(), CLIMBER, 3, 4, 6, 7).reachedGoal(), "up and off");
        assertTrue(find(ladderUpAWall(), CLIMBER, 3, 4, 0, 1).reachedGoal(), "down and away");
    }

    @Test
    void aHoldIsLeftByTheRungsOrStraightOffTheSideNeverOnASlant() {
        AsciiWorld pit = pitWithLadders();
        Path path = find(pit, CLIMBER, 3, 4, 10, 7);
        assertTrue(path.reachedGoal());
        Waypoint from = new Waypoint(3, 4, 0, MoveType.CLIMB);
        for (Waypoint to : path.waypoints()) {
            if (hanging(pit, from)) {
                int reach = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
                assertTrue(reach <= 1, "from a rung, one cell at most and never diagonal: " + to);
            }
            from = to;
        }
    }

    @Test
    void aSearchThatRunsOutNeverLeavesTheBodyOnTheLadder() {
        // The ladder stops two rungs short of a wall eight high: the rungs are the nearest the body
        // gets to the top, and the one place it cannot be left.
        AsciiWorld tooShort = AsciiWorld.of("1111999").climb(3, 1, 0, 3, 6, 0);
        Path path = find(tooShort, CLIMBER, 0, 1, 6, 9);
        assertFalse(path.reachedGoal());
        assertFalse(hanging(tooShort, path.last()), "ended on a rung: " + path.last());
    }

    @Test
    void vinesDownACliffAreClimbedToo() {
        AsciiWorld cliff = AsciiWorld.of("77711111").climb(3, 1, 0, 3, 6, 0);
        Path path = find(cliff, CLIMBER, 0, 7, 6, 1);
        assertTrue(path.reachedGoal());
        assertTrue(moves(path).contains(MoveType.CLIMB));
    }

    // ── scaffolding ─────────────────────────────────────────────────────────────────────────

    /** A scaffolding tower six high at {@code x = 2}, on flat ground. */
    private static AsciiWorld scaffoldingTower() {
        return AsciiWorld.of("11111").scaffolding(2, 1, 0, 2, 6, 0);
    }

    @Test
    void aScaffoldingTowerIsClimbedInsideAndStoodOnTop() {
        Path up = find(scaffoldingTower(), CLIMBER, 0, 1, 2, 7);
        assertTrue(up.reachedGoal(), "onto the top of it");
        assertTrue(moves(up).contains(MoveType.CLIMB));
        assertFalse(find(scaffoldingTower(), NO_CLIMB, 0, 1, 2, 7).reachedGoal());
    }

    @Test
    void aScaffoldingTowerIsSunkThroughToComeDown() {
        Path down = find(scaffoldingTower(), CLIMBER, 2, 7, 0, 1);
        assertTrue(down.reachedGoal());
        assertTrue(down.waypoints().contains(new Waypoint(2, 6, 0, MoveType.CLIMB)),
                "into the top level, not off the side: " + down.waypoints());
    }

    @Test
    void aScaffoldingPlatformIsWalkedOn() {
        // A floor of scaffolding over a gap too wide to leap, standing on nothing but itself.
        AsciiWorld bridge = AsciiWorld.of("5     5").scaffolding(1, 4, 0, 5, 4, 0);
        Path over = find(bridge, NO_CLIMB, 0, 5, 6, 5);
        assertTrue(over.reachedGoal(), "a floor is a floor, climber or not");
    }

    // ── hatches ─────────────────────────────────────────────────────────────────────────────

    /**
     * A floor five up over a ladder, with a shut trapdoor in the hole the ladder comes up through —
     * top-half (a full block of floor) or bottom-half (a sliver at the bottom of the hole).
     */
    private static AsciiWorld hatchShaft(double surface) {
        return AsciiWorld.of("11111111")
                .fill(0, 6, 0, 7, 6, 0, CellType.GROUND)
                .climb(3, 1, 0, 3, 5, 0)
                .hatch(3, 6, 0, surface);
    }

    @Test
    void aHatchIsSwungOpenAndClimbedThrough() {
        Path up = find(hatchShaft(1.0), CLIMBER, 0, 1, 6, 7);
        assertTrue(up.reachedGoal(), "up the ladder and through the hatch: " + up.waypoints());
        assertTrue(up.waypoints().contains(new Waypoint(3, 6, 0, MoveType.CLIMB)));
        MoveCapabilities noHands = new MoveCapabilities(1.8, 1, 3, 3, true, 36, false, true);
        assertFalse(find(hatchShaft(1.0), noHands, 0, 1, 6, 7).reachedGoal(),
                "shut, a hatch is a floor to a body that cannot swing it");
    }

    @Test
    void aHatchIsSwungOpenAndClimbedDownThrough() {
        assertTrue(find(hatchShaft(1.0), CLIMBER, 6, 7, 0, 1).reachedGoal());
        assertTrue(find(hatchShaft(0.1875), CLIMBER, 6, 7, 0, 1).reachedGoal(),
                "a trapdoor in the bottom of the hole too");
        assertTrue(find(hatchShaft(0.1875), CLIMBER, 0, 1, 6, 7).reachedGoal());
    }

    @Test
    void aShutTrapdoorOverALadderWithoutTheHatchMarkIsJustAFloor() {
        // What the classifier writes for an iron trapdoor, or one over a ladder facing another way.
        AsciiWorld iron = AsciiWorld.of("11111111")
                .fill(0, 6, 0, 7, 6, 0, CellType.GROUND)
                .climb(3, 1, 0, 3, 5, 0);
        assertFalse(find(iron, CLIMBER, 0, 1, 6, 7).reachedGoal());
    }
}
