package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link PathRequest#varying}: two bodies sent to one place walk different lines, and neither
 * walks a line worth complaining about.
 *
 * <p>The two halves are tested apart because either alone is trivially satisfiable — always
 * answering the same thing keeps routes cheap, wandering keeps them different. The bound is
 * asserted twice: as the 2% the mechanism guarantees, and as the number actually measured, an
 * order of magnitude better.
 */
class PathfinderVarietyTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED;

    /** Arbitrary, and that is the point — these stand in for whatever an agent's id hashes to. */
    private static final long[] SEEDS = {1L, 7L, 12345L, -98765L, 8675309L, Long.MIN_VALUE};

    /**
     * What the mechanism promises: no route is worse than this over the genuine best. It is
     * {@code (1 + ROUGHNESS/2) × (1 + ROUGHNESS) - 1} — the prices bent by up to 2%, and the
     * heuristic meeting them halfway so the search stays affordable.
     */
    private static final double BOUND = 1.01 * 1.02 - 1.0;

    /** A featureless plain: every route across it is a matter of taste, not of terrain. */
    private static AsciiWorld plain(int size) {
        String[] rows = new String[size];
        for (int z = 0; z < size; z++) {
            rows[z] = "1".repeat(size);
        }
        return AsciiWorld.of(rows);
    }

    private static Path walk(NavGrid world, int gx, int gz, long variety) {
        return Pathfinder.find(world, PathRequest.of(0, 1, 0, gx, 1, gz, BODY).varying(variety));
    }

    /**
     * What a route over flat, dry, edge-free ground costs: every move a WALK priced at its
     * Euclidean length (cardinal 1, diagonal √2, stride its reach), terrain factor one — which is
     * why the world under it has no water, no ledge and nothing to fear in it.
     */
    private static double cost(Path path) {
        double total = 0.0;
        int x = 0;
        int z = 0;
        for (Waypoint w : path.waypoints()) {
            total += Math.hypot(w.x() - x, w.z() - z);
            x = w.x();
            z = w.z();
        }
        return total;
    }

    @Test
    void settlersSentToOnePlaceDoNotWearOneRut() {
        AsciiWorld world = plain(48);
        Set<List<Waypoint>> routes = new HashSet<>();
        for (long seed : SEEDS) {
            Path path = walk(world, 47, 24, seed);
            assertTrue(path.reachedGoal(), "seed " + seed + " should still reach the goal");
            routes.add(path.waypoints());
        }
        assertTrue(routes.size() >= SEEDS.length - 1,
                "seeded searches should nearly all differ; " + routes.size() + " of " + SEEDS.length
                        + " were distinct");
    }

    @Test
    void noSeedWalksARouteWorseThanTheGuaranteedBound() {
        AsciiWorld world = plain(48);
        // Several shapes of trip, because the ways a route can go wrong are directional: a straight
        // cardinal run, a perfect diagonal, and two that line up with no stride in the table.
        for (int[] goal : new int[][] {{47, 0}, {47, 47}, {47, 24}, {35, 19}}) {
            double best = cost(walk(world, goal[0], goal[1], 0L));
            for (long seed : SEEDS) {
                double walked = cost(walk(world, goal[0], goal[1], seed));
                assertTrue(walked <= best * (1.0 + BOUND) + 1e-9,
                        "seed " + seed + " to (" + goal[0] + "," + goal[1] + ") walked " + walked
                                + " against a best of " + best + " — past the " + BOUND + " bound");
            }
        }
    }

    @Test
    void andInPracticeIsNowhereNearIt() {
        // The bound above is the theorem; this is the measurement. Liked and disliked ground largely
        // cancel, so the real detour was about 0.2% over these trips when this was written. A
        // quarter of the bound leaves room to drift while still catching roughness ceasing to be
        // coherent (per-cell noise, or a patch shrunk to nothing), which would stop cancelling.
        AsciiWorld world = plain(48);
        double worst = 0.0;
        for (int[] goal : new int[][] {{47, 0}, {47, 47}, {47, 24}, {35, 19}}) {
            double best = cost(walk(world, goal[0], goal[1], 0L));
            for (long seed : SEEDS) {
                worst = Math.max(worst, cost(walk(world, goal[0], goal[1], seed)) / best - 1.0);
            }
        }
        assertTrue(worst < BOUND / 4.0,
                "the measured detour has crept up to " + worst + "; it was about 0.002");
    }

    @Test
    void oneAgentAlwaysWalksTheSameLine() {
        // The property that keeps a body from dithering: a long trip is re-planned leg by leg, and
        // an opinion of the ground re-drawn each time would send it back and forth around the same
        // obstacle for as long as it lived.
        AsciiWorld world = plain(48);
        assertEquals(walk(world, 47, 24, 4242L).waypoints(), walk(world, 47, 24, 4242L).waypoints());
    }

    @Test
    void seedZeroIsTheAnswerTheSearchAlwaysGave() {
        // Every test, tool and capture in this repo goes through the unseeded factory. This is the
        // promise that none of them moved — the path-integrity pair and the gauntlet's recorded
        // column both depend on it.
        AsciiWorld world = plain(48);
        assertEquals(Pathfinder.find(world, PathRequest.of(0, 1, 0, 47, 1, 24, BODY)).waypoints(),
                walk(world, 47, 24, 0L).waypoints());
    }

    @Test
    void aWallIsStillWalkedRoundRatherThanThrough() {
        // Taste in ground must never amount to permission. A wall across the map with its doorway
        // in the far corner: a seed that shortcut it would come back cheaper than the detour, and
        // one that could not find the doorway would not come back at all.
        AsciiWorld world = plain(9).fill(0, 1, 4, 7, 3, 4, CellType.OBSTACLE);
        for (long seed : SEEDS) {
            Path path = Pathfinder.find(world,
                    PathRequest.of(0, 1, 0, 0, 1, 8, BODY).varying(seed));
            assertTrue(path.reachedGoal(), "seed " + seed + " should find the doorway");
            assertTrue(cost(path) > 8.0,
                    "seed " + seed + " got through the wall somehow: " + path.waypoints());
        }
    }
}
