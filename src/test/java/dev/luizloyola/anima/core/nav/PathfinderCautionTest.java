package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * What a walk's {@link Caution} does to its route: a stroll refuses every cell one slip from harm
 * and every leap over a gap that would hurt, an errand pays for them, the bare search does neither.
 */
class PathfinderCautionTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;

    private static Path find(NavGrid grid, int sx, int sy, int sz, int gx, int gy, int gz,
            Caution caution) {
        return Pathfinder.find(grid, PathRequest.of(sx, sy, sz, gx, gy, gz, BODY).cautious(caution));
    }

    private static boolean touchesHarm(NavGrid grid, Path path, boolean drops) {
        return walked(path).stream()
                .anyMatch(c -> NavGrids.besideHarm(grid, BODY, c[0], c[1], c[2], drops));
    }

    /**
     * Every cell a route sets foot in, the cells a stride sweeps between its waypoints included —
     * not a leap's, which it flies over.
     */
    static java.util.List<int[]> walked(Path path) {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        Waypoint last = null;
        for (Waypoint w : path.waypoints()) {
            if (last != null && w.move() != MoveType.LEAP) {
                int dx = w.x() - last.x();
                int dz = w.z() - last.z();
                int steps = Math.max(Math.abs(dx), Math.abs(dz));
                for (int i = 1; i < steps; i++) {
                    cells.add(new int[] {last.x() + Math.round((float) i * dx / steps), last.y(),
                            last.z() + Math.round((float) i * dz / steps)});
                }
            }
            cells.add(new int[] {w.x(), w.y(), w.z()});
            last = w;
        }
        return cells;
    }

    private static boolean leaps(Path path) {
        return path.waypoints().stream().anyMatch(w -> w.move() == MoveType.LEAP);
    }

    /** A field with a lava stream along z = 3 from x 2 to 9, level with the feet. */
    private static AsciiWorld streamBesideTheLane() {
        return AsciiWorld.of(
                "111111111111",
                "111111111111",
                "111111111111",
                "111111111111",
                "111111111111",
                "111111111111")
                .fill(2, 1, 3, 9, 1, 3, CellType.DANGER);
    }

    @Test
    void theBareSearchWalksAlongsideTheLava() {
        NavGrid world = streamBesideTheLane();
        Path path = find(world, 0, 1, 2, 11, 1, 2, Caution.NONE);
        assertTrue(path.reachedGoal());
        assertTrue(touchesHarm(world, path, false), "the straight line runs beside the stream");
    }

    @Test
    void anErrandAndAStrollKeepACellOffTheLava() {
        NavGrid world = streamBesideTheLane();
        for (Caution caution : new Caution[] {Caution.ERRAND, Caution.STROLL}) {
            Path path = find(world, 0, 1, 2, 11, 1, 2, caution);
            assertTrue(path.reachedGoal(), caution.name());
            assertFalse(touchesHarm(world, path, false), caution + " keeps a cell between it and lava");
        }
    }

    /** A corridor between walls, with one lava cell in the north wall at body height. */
    private static AsciiWorld lavaInTheOnlyCorridor() {
        return AsciiWorld.of(
                "#########",
                "111111111",
                "#########")
                .fill(4, 1, 0, 4, 2, 0, CellType.DANGER);
    }

    @Test
    void anErrandWithNoWayRoundPassesTheLavaAndAStrollDoesNot() {
        NavGrid world = lavaInTheOnlyCorridor();
        assertTrue(find(world, 0, 1, 1, 8, 1, 1, Caution.ERRAND).reachedGoal(),
                "dear, never refused: the work may need it");
        assertFalse(find(world, 0, 1, 1, 8, 1, 1, Caution.STROLL).reachedGoal());
    }

    /** A bottomless trench two wide at x 4 and 5, from z 0 to {@code to}; the rest is field. */
    private static AsciiWorld trench(int to) {
        String[] rows = new String[9];
        for (int z = 0; z < rows.length; z++) {
            rows[z] = z <= to ? "1111  1111" : "1111111111";
        }
        return AsciiWorld.of(rows);
    }

    @Test
    void theBareSearchLeapsAGapItWouldBeHurtIn() {
        assertTrue(leaps(find(trench(3), 0, 1, 1, 9, 1, 1, Caution.NONE)));
    }

    @Test
    void anErrandGoesRoundAGapItWouldBeHurtInWhenItCan() {
        Path path = find(trench(3), 0, 1, 1, 9, 1, 1, Caution.ERRAND);
        assertTrue(path.reachedGoal());
        assertFalse(leaps(path), "round the trench's end");
    }

    @Test
    void anErrandWithNoWayRoundLeapsAndAStrollDoesNot() {
        assertTrue(leaps(find(trench(8), 0, 1, 1, 9, 1, 1, Caution.ERRAND)));
        assertFalse(find(trench(8), 0, 1, 1, 9, 1, 1, Caution.STROLL).reachedGoal());
    }

    @Test
    void aStrollDoesNotCrossABridgeOverADropThatHurts() {
        NavGrid world = AsciiWorld.of(
                "555     555",
                "55555555555",
                "555     555");
        assertTrue(find(world, 0, 5, 1, 10, 5, 1, Caution.ERRAND).reachedGoal());
        assertFalse(find(world, 0, 5, 1, 10, 5, 1, Caution.STROLL).reachedGoal());
    }

    /**
     * Lloyd's last walk (forest, 2026-10-02), on the cave as captured: from the wet floor at
     * (1021, 28, 1763) round to (1014, 30, 1763), past a lava pool on the floor at y 25 and the
     * stream running down from it. The bare route walks the pool's edge; he burned there.
     */
    @Test
    void theCaveWalkThatBurnedKeepsOffThePool() throws IOException {
        NavGrid cave;
        try (InputStream in = getClass().getResourceAsStream("/nav/cave-lava.txt")) {
            assertNotNull(in, "missing fixture /nav/cave-lava.txt");
            cave = CapturedWorld.parse(CapturedWorld.lines(in));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Path bare = find(cave, 1021, 28, 1763, 1014, 30, 1763, Caution.NONE);
        assertTrue(bare.reachedGoal());
        assertTrue(touchesHarm(cave, bare, false), "the route as it was walked");
        for (Caution caution : new Caution[] {Caution.ERRAND, Caution.STROLL}) {
            Path path = find(cave, 1021, 28, 1763, 1014, 30, 1763, caution);
            assertTrue(path.reachedGoal(), caution.name());
            assertFalse(touchesHarm(cave, path, false), caution.name());
        }
    }
}
