package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stairs — a {@link CellType#GROUND} block with {@link NavGrid#ramps}. A stair reads as a full
 * block, so until 2026-09-26 a staircase was planned as a run of jumps up and one-block drops down;
 * its low tread makes each of those two half steps, which is a walk.
 */
class PathfinderRampTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;

    /** Four stairs up to the east, each block on its own column one higher than the last. */
    private static AsciiWorld staircase(boolean ramps) {
        AsciiWorld world = AsciiWorld.of("123455");
        if (ramps) {
            for (int x = 1; x <= 4; x++) {
                world.stair(x, x, 0, NavGrid.EAST);
            }
        }
        return world;
    }

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, BODY));
    }

    private static List<MoveType> moves(Path path) {
        return path.waypoints().stream().map(Waypoint::move).toList();
    }

    @Test
    void aStaircaseIsWalkedUpNotHopped() {
        Path up = find(staircase(true), 0, 1, 0, 5, 5, 0);
        assertTrue(up.reachedGoal());
        assertFalse(moves(up).contains(MoveType.JUMP), moves(up).toString());

        Path blocks = find(staircase(false), 0, 1, 0, 5, 5, 0);
        assertTrue(moves(blocks).contains(MoveType.JUMP),
                "the same steps as full blocks are jumps — otherwise this proves nothing");
    }

    @Test
    void aStaircaseIsWalkedDownNotDropped() {
        Path down = find(staircase(true), 5, 5, 0, 0, 1, 0);
        assertTrue(down.reachedGoal());
        assertFalse(moves(down).contains(MoveType.DROP), moves(down).toString());
        assertTrue(moves(find(staircase(false), 5, 5, 0, 0, 1, 0)).contains(MoveType.DROP));
    }

    @Test
    void aStairTakenFromTheSideIsStillAJump() {
        // One stair facing north in a flat field: its low tread is on its south side.
        AsciiWorld field = AsciiWorld.of(
                "111",
                "121",
                "111").stair(1, 1, 1, NavGrid.NORTH);
        assertEquals(List.of(new Waypoint(1, 2, 1, MoveType.WALK)),
                find(field, 1, 1, 2, 1, 2, 1).waypoints(), "up from the south: a walk");
        assertEquals(List.of(new Waypoint(1, 2, 1, MoveType.JUMP)),
                find(field, 0, 1, 1, 1, 2, 1).waypoints(), "up from the west: a jump");
    }

    @Test
    void aStairUnderALowCeilingIsRefusedLikeTheJumpItReplaces() {
        // Walking up a stair, the head is still over the step behind as the feet reach the next
        // tread, and rises exactly as far as a jump's would.
        AsciiWorld low = AsciiWorld.of("12").stair(1, 1, 0, NavGrid.EAST)
                .fill(0, 3, 0, 0, 3, 0, CellType.GROUND);
        assertFalse(find(low, 0, 1, 0, 1, 2, 0).reachedGoal());
        AsciiWorld high = AsciiWorld.of("12").stair(1, 1, 0, NavGrid.EAST)
                .fill(0, 4, 0, 0, 4, 0, CellType.GROUND);
        assertTrue(find(high, 0, 1, 0, 1, 2, 0).reachedGoal());
    }
}
