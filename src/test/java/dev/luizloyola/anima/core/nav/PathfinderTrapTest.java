package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Whether a route leaves the body somewhere it cannot walk out of ({@link Path#trapped}): a body
 * that fled a zombie into a moat was only shut in with the next thing to fall in (2026-09-30).
 *
 * <p>Every world is a field at height 4, {@value #SIZE} across and bounded as a capture is, so open
 * ground reaches the rim; a pit three deep sits in the middle, x and z 10 to 14. The body starts in
 * the north field at (12, 4, 2).
 */
class PathfinderTrapTest {

    private static final int SIZE = 25;
    private static final int LO = 10;
    private static final int HI = 14;

    @Test
    void aDropIntoAPitWithNoWayUpIsATrap() {
        Path path = find(field(false, false), 12, 1, 12);
        assertTrue(path.reachedGoal());
        assertTrue(path.trapped());
    }

    @Test
    void aRavineThatRunsPastTheEdgeOfTheCaptureIsATrap() {
        // Nothing proves a ravine closed: it leaves the capture at both ends. What is missing is a
        // way back, which is what the moat on the shelter scene was missing too.
        String[] rows = new String[SIZE];
        for (int z = 0; z < SIZE; z++) {
            rows[z] = (z >= LO && z <= LO + 2 ? "1" : "4").repeat(SIZE);
        }
        Path path = find(AsciiWorld.of(rows), 12, 1, 11);
        assertTrue(path.reachedGoal());
        assertTrue(path.trapped());
    }

    @Test
    void aHollowWithStepsOutIsNot() {
        Path path = find(field(true, false), 12, 1, 12);
        assertTrue(path.reachedGoal());
        assertFalse(path.trapped(), "the steps on the west side climb back out");
    }

    @Test
    void aRouteAcrossLevelGroundIsNeverATrap() {
        Path path = find(field(false, false), 20, 4, 2);
        assertTrue(path.reachedGoal());
        assertFalse(path.trapped());
    }

    @Test
    void aDropWithAWayBackUpIsNot() {
        // A platform at height 3 inside the pit, up two steps from its floor: dropping off it is a
        // drop, but the steps lead back up to it.
        Path path = Pathfinder.find(field(false, true).bounded(),
                PathRequest.of(11, 3, 12, 13, 1, 12, TestBodies.BIPED));
        assertTrue(path.reachedGoal());
        assertFalse(path.trapped(), "there is a way back to where it started");
        assertTrue(Pathfinder.find(field(false, true).bounded(),
                PathRequest.of(12, 4, 2, 13, 1, 12, TestBodies.BIPED)).trapped(),
                "the same pit is a trap to a body coming from the field");
    }

    private static Path find(AsciiWorld world, int gx, int gy, int gz) {
        return Pathfinder.find(world.bounded(), PathRequest.of(12, 4, 2, gx, gy, gz,
                TestBodies.BIPED));
    }

    /**
     * The field and its pit. With steps, a stair up the west side climbs out; with a platform, a
     * cell at height 3 inside the pit, up a step at height 2, that climbs nowhere.
     */
    private static AsciiWorld field(boolean steps, boolean platform) {
        String[] rows = new String[SIZE];
        for (int z = 0; z < SIZE; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < SIZE; x++) {
                boolean pit = x >= LO && x <= HI && z >= LO && z <= HI;
                char c = pit ? '1' : '4';
                if (platform && z == 12 && x == LO) {
                    c = '2';
                } else if (platform && z == 12 && x == LO + 1) {
                    c = '3';
                } else if (steps && z == 12 && x == LO - 1) {
                    c = '2';
                } else if (steps && z == 12 && x == LO - 2) {
                    c = '3';
                }
                row.append(c);
            }
            rows[z] = row.toString();
        }
        return AsciiWorld.of(rows);
    }
}
