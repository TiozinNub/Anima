package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@link Surface} in a route: a stroll never goes further under it than it set out, an errand
 * pays to go under it unless its goal is down there, the bare search does not ask.
 */
class PathfinderSurfaceTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;

    private static Path find(NavGrid grid, int sx, int sy, int sz, int gx, int gy, int gz,
            Caution caution) {
        return Pathfinder.find(grid, PathRequest.of(sx, sy, sz, gx, gy, gz, BODY).cautious(caution));
    }

    private static boolean goesUnder(NavGrid grid, Path path) {
        return PathfinderCautionTest.walked(path).stream()
                .anyMatch(c -> Surface.under(grid, c[0], c[1], c[2]));
    }

    /**
     * A ravine across the whole map along z: plateaus at 9, sides stepping down a block a column,
     * a floor at 2 five wide — seven under its rim, past any leap. The floor is the only way across.
     */
    private static NavGrid ravine() {
        String row = "9999876543222223456789999";
        return AsciiWorld.of(row, row, row, row, row).natural();
    }

    @Test
    void theBareSearchAndAnErrandCrossTheRavineFloor() {
        NavGrid world = ravine();
        Path bare = find(world, 0, 9, 2, 24, 9, 2, Caution.NONE);
        assertTrue(bare.reachedGoal());
        assertTrue(goesUnder(world, bare), "the floor is under the surface");
        assertTrue(find(world, 0, 9, 2, 24, 9, 2, Caution.ERRAND).reachedGoal(),
                "dear, never refused");
    }

    @Test
    void aStrollDoesNotGoDownIntoTheRavine() {
        assertFalse(find(ravine(), 0, 9, 2, 24, 9, 2, Caution.STROLL).reachedGoal());
    }

    @Test
    void aStrollOnTheFloorWalksAlongOrUpButNeverDown() {
        NavGrid world = ravine();
        assertTrue(find(world, 12, 2, 2, 13, 2, 4, Caution.STROLL).reachedGoal(), "along it");
        assertTrue(find(world, 12, 2, 2, 7, 5, 2, Caution.STROLL).reachedGoal(), "up the side");
        assertFalse(find(world, 8, 4, 2, 12, 2, 2, Caution.STROLL).reachedGoal(), "back down");
    }

    /**
     * A hill seven high from x 3 to 9 and z 0 to 6, a tunnel through it at z 2, open field round
     * it to the south: through is twelve steps, round about eighteen.
     */
    private static NavGrid hillWithATunnel() {
        String hill = "2229999999222";
        String field = "2222222222222";
        AsciiWorld map = AsciiWorld.of(hill, hill, hill, hill, hill, hill, hill, field, field, field);
        return map.fill(3, 2, 2, 9, 3, 2, CellType.PASSABLE).natural();
    }

    @Test
    void theBareSearchTakesTheTunnel() {
        NavGrid world = hillWithATunnel();
        assertTrue(goesUnder(world, find(world, 0, 2, 2, 12, 2, 2, Caution.NONE)));
    }

    @Test
    void anErrandGoesRoundTheHillAndAStrollMustTo() {
        NavGrid world = hillWithATunnel();
        for (Caution caution : new Caution[] {Caution.ERRAND, Caution.STROLL}) {
            Path path = find(world, 0, 2, 2, 12, 2, 2, caution);
            assertTrue(path.reachedGoal(), caution.name());
            assertFalse(goesUnder(world, path), caution + " keeps out of the tunnel");
        }
    }

    /**
     * A hill thirteen long with a tunnel through it at z 2, field round it to the south: the
     * goal at (14, 2, 2) is a cell short of the far mouth.
     */
    private static NavGrid longHill() {
        String hill = "222" + "9".repeat(13) + "222";
        String field = "2".repeat(19);
        AsciiWorld map = AsciiWorld.of(hill, hill, hill, hill, hill, hill, hill, field, field, field);
        return map.fill(3, 2, 2, 15, 3, 2, CellType.PASSABLE).natural();
    }

    @Test
    void anErrandWhoseWorkIsDownThereTakesTheShortWayIn() {
        // Charged for the tunnel, the walk round to the far mouth would be the cheaper.
        NavGrid world = longHill();
        Path path = find(world, 0, 2, 2, 14, 2, 2, Caution.ERRAND);
        assertTrue(path.reachedGoal());
        assertTrue(PathfinderCautionTest.walked(path).stream().anyMatch(c -> c[0] == 4 && c[2] == 2),
                "in at the near mouth");
    }

    @Test
    void anErrandWhoseWorkIsInTheTunnelGoesIn() {
        NavGrid world = hillWithATunnel();
        assertTrue(find(world, 0, 2, 2, 6, 2, 2, Caution.ERRAND).reachedGoal());
        assertFalse(find(world, 0, 2, 2, 6, 2, 2, Caution.STROLL).reachedGoal());
    }

    /**
     * Lloyd's way down (forest, 2026-10-02), on the pit as captured with its natural tops: a stroll
     * from (1029, 72, 1742) to a spot on its rim whose route swam the pit's floor eleven under the
     * surface. Interrupted there, he wandered the floor down into the cave where he burned.
     */
    @Test
    void theStrollThatTookLloydDownTheSinkholeGoesRoundIt() throws java.io.IOException {
        NavGrid pit;
        try (java.io.InputStream in = getClass().getResourceAsStream("/nav/pit-stroll.txt")) {
            org.junit.jupiter.api.Assertions.assertNotNull(in, "missing fixture /nav/pit-stroll.txt");
            pit = CapturedWorld.parse(CapturedWorld.lines(in));
        }
        Path bare = find(pit, 1029, 72, 1742, 1025, 64, 1746, Caution.NONE);
        assertTrue(bare.reachedGoal());
        assertTrue(goesUnder(pit, bare), "the route as it was walked");
        for (Caution caution : new Caution[] {Caution.ERRAND, Caution.STROLL}) {
            Path path = find(pit, 1029, 72, 1742, 1025, 64, 1746, caution);
            assertTrue(path.reachedGoal(), caution.name());
            assertFalse(goesUnder(pit, path), caution.name());
        }
        assertFalse(find(pit, 1031, 67, 1732, 1024, 53, 1745, Caution.STROLL).reachedGoal(),
                "the floor he wandered next, seventeen under");
    }
}
