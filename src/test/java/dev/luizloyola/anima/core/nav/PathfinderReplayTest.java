package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The building search replaying the first search's moves: the same route in every case, and most
 * of its cells replayed rather than probed. Reports timings rather than asserting them.
 */
class PathfinderReplayTest {

    private static final MoveCapabilities BUILDER = TestBodies.BIPED.withLaid(16).withScaling(true);
    private static final long[] SEEDS = {0L, 1L, 7L, 0x5EEDL};

    /** Replayed and probed choose the same route, and the building searches agree in full. */
    private static Pathfinder.Searches assertSameChoice(NavGrid grid, PathRequest request) {
        Path replayed = Pathfinder.find(grid, request, true);
        Path probed = Pathfinder.find(grid, request, false);
        assertEquals(probed.waypoints(), replayed.waypoints(), "replaying changed the route");
        assertEquals(probed.reachedGoal(), replayed.reachedGoal());
        assertEquals(probed.spent(), replayed.spent());
        Pathfinder.Searches tape = Pathfinder.searches(grid, request, true);
        Pathfinder.Searches probe = Pathfinder.searches(grid, request, false);
        if (probe.built() != null) {
            assertEquals(probe.built(), tape.built(), "the building search answered differently");
            assertEquals(probe.probed(), tape.probed() + tape.replayed());
        }
        return tape;
    }

    private static boolean swims(Path path) {
        return path.waypoints().stream().anyMatch(w -> w.move().inWater());
    }

    // ── a lake ───────────────────────────────────────────────────────────────────────────────

    /** Twelve strokes of open water, a gap a deck could span beside it, banks either side. */
    private static NavGrid lake() {
        String[] rows = new String[9];
        for (int z = 0; z < 8; z++) {
            rows[z] = "111WWWWWWWWWWWW111";
        }
        rows[8] = "111            111";
        return AsciiWorld.of(rows).bounded();
    }

    @Test
    void aLakeIsSwumAlikeAndMostlyReplayed() {
        NavGrid grid = lake();
        for (long seed : SEEDS) {
            PathRequest request = PathRequest.of(1, 1, 4, 16, 1, 4, BUILDER).varying(seed);
            Pathfinder.Searches tape = assertSameChoice(grid, request);
            assertTrue(swims(tape.first()) && tape.first().reachedGoal(), "the lake is swum");
            assertNotNull(tape.built(), "a swim reads as a detour, so building is asked");
            assertTrue(tape.replayed() > 3 * tape.probed(),
                    () -> tape.replayed() + " replayed, " + tape.probed() + " probed");
            assertEquals(0, Pathfinder.find(grid, request).laid(), "and nothing is laid");
        }
    }

    // ── a gap, and the way round at every depth ──────────────────────────────────────────────

    /** A gap four wide, with the way round {@code depth} rows down. */
    private static NavGrid wayRound(int depth) {
        String[] rows = new String[depth + 1];
        for (int z = 0; z < depth; z++) {
            rows[z] = "1111    1111";
        }
        rows[depth] = "111111111111";
        return AsciiWorld.of(rows).bounded();
    }

    /** Every depth from a short way round to a long one, across where bridging starts to win. */
    @Test
    void everyWayRoundChoosesAlikeWalkedOrBridged() {
        int walked = 0;
        int bridged = 0;
        for (int depth = 2; depth <= 24; depth++) {
            NavGrid grid = wayRound(depth);
            for (long seed : SEEDS) {
                PathRequest request = PathRequest.of(1, 1, 0, 10, 1, 0, BUILDER).varying(seed);
                Pathfinder.Searches tape = assertSameChoice(grid, request);
                Path chosen = Pathfinder.find(grid, request);
                assertTrue(chosen.reachedGoal(), "depth " + depth);
                if (chosen.laid() > 0) {
                    bridged++;
                } else if (tape.built() != null) {
                    walked++;
                }
            }
        }
        assertTrue(walked > 0, "some depth walks round after asking about building");
        assertTrue(bridged > 0, "some depth bridges");
    }

    @Test
    void aLongWayRoundIsStillBridged() {
        NavGrid grid = wayRound(19);
        PathRequest request = PathRequest.of(1, 1, 1, 10, 1, 1, BUILDER);
        assertSameChoice(grid, request);
        Path path = Pathfinder.find(grid, request);
        assertTrue(path.reachedGoal());
        assertEquals(4, path.laid(), () -> "bridged: " + path.waypoints());
    }

    /**
     * A cell both searches close, arrived at differently: walked up a strip from the south by the
     * first, off a deck from the west by the building one, which may then leap east off it
     * straight away. Replay the first's moves there and the leap is gone: three more decks.
     */
    @Test
    void aCellReachedOffADeckLeapsAsArrived() {
        String[] rows = new String[20];
        rows[0] = "1111    1   1111";
        for (int z = 1; z < 19; z++) {
            rows[z] = "1111    1###1111";
        }
        rows[19] = "111111111###1111";
        NavGrid grid = AsciiWorld.of(rows).bounded();
        PathRequest request = PathRequest.of(1, 1, 0, 13, 1, 0, BUILDER);
        Pathfinder.Searches tape = assertSameChoice(grid, request);
        assertTrue(!tape.first().reachedGoal(), "nothing but a leap off the strip's end crosses");
        Path path = Pathfinder.find(grid, request);
        assertEquals(4, path.laid(), () -> "bridged: " + path.waypoints());
        assertTrue(path.waypoints().stream().anyMatch(w -> w.move() == MoveType.LEAP),
                () -> "and leapt: " + path.waypoints());
    }

    // ── a recorded pillar under the first route ──────────────────────────────────────────────

    /**
     * The first route drops off a recorded pillar; the building search may not, and goes down it.
     * Replaying the first search's moves out of the pillar top would hand it the drop.
     */
    @Test
    void aRecordedPillarUnderTheFirstRouteIsStillGoneDown() {
        NavGrid grid = AsciiWorld.of("111", "111", "111").fill(1, 1, 1, 1, 3, 1, CellType.GROUND)
                .bounded();
        Set<Long> pillar = Set.of(LaidBlocks.cell(1, 1, 1), LaidBlocks.cell(1, 2, 1),
                LaidBlocks.cell(1, 3, 1));
        PathRequest request = PathRequest.of(1, 4, 1, 2, 1, 1, TestBodies.BIPED.withScaling(true))
                .near(pillar);
        assertSameChoice(grid, request);
        Path path = Pathfinder.find(grid, request);
        assertTrue(path.reachedGoal());
        assertEquals(3, path.waypoints().stream().filter(w -> w.move() == MoveType.LOWER).count(),
                () -> "down the pillar: " + path.waypoints());
    }

    // ── timings ──────────────────────────────────────────────────────────────────────────────

    private static final int SIZE = 96;

    /** Banks at feet y 1, a lake two deep across sixteen columns. Arrays, for speed. */
    private static final class Lake implements NavGrid {
        private final int from;
        private final int to;

        Lake(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public CellType cell(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return CellType.OBSTACLE;
            boolean water = x >= this.from && x < this.to;
            int floor = water ? -1 : 1;
            if (y < floor) return CellType.GROUND;
            return water && y <= 0 ? CellType.WATER : CellType.PASSABLE;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            return x >= 0 && z >= 0 && x < SIZE && z < SIZE;
        }
    }

    /** Flat ground with a two-high trunk on about one cell in seven, placed by a hash. */
    private static final class Woods implements NavGrid {
        @Override
        public CellType cell(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return CellType.OBSTACLE;
            if (y < 1) return CellType.GROUND;
            long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            boolean trunk = (h >>> 40) % 7 == 0 && x > 2 && z > 2;
            return trunk && y <= 2 ? CellType.OBSTACLE : CellType.PASSABLE;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            return x >= 0 && z >= 0 && x < SIZE && z < SIZE;
        }
    }

    private static double timed(NavGrid grid, PathRequest request, boolean replay) {
        for (int i = 0; i < 20; i++) {
            Pathfinder.find(grid, request, replay);
        }
        int runs = 40;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            Pathfinder.find(grid, request, replay);
        }
        return (System.nanoTime() - start) / 1e6 / runs;
    }

    private static void report(String scene, NavGrid grid, PathRequest request) {
        Pathfinder.Searches tape = assertSameChoice(grid, request);
        double probedMs = timed(grid, request, false);
        double replayedMs = timed(grid, request, true);
        System.out.printf("%s: first search %d closed, building search %s; probed %.3f ms/find, "
                        + "replayed %.3f ms/find%n", scene, tape.first().reachableCells(),
                tape.built() == null ? "not asked"
                        : tape.replayed() + " replayed " + tape.probed() + " probed",
                probedMs, replayedMs);
    }

    @Test
    void timings() {
        report("lake", new Lake(40, 56), PathRequest.of(36, 1, 48, 60, 1, 48, BUILDER).varying(7L));
        report("wide lake, budget spent", new Lake(28, 68),
                PathRequest.of(20, 1, 48, 76, 1, 48, BUILDER).varying(7L));
        report("dry detour", wayRound(8), PathRequest.of(1, 1, 0, 10, 1, 0, BUILDER).varying(7L));
        report("woods, no second search", new Woods(),
                PathRequest.of(1, 1, 1, 90, 1, 90, BUILDER).varying(7L));
    }
}
