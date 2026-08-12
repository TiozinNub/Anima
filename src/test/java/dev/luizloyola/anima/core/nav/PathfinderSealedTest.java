package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The confinement verdict: a search that runs out of anywhere to go has enumerated everything
 * the body can reach — a proof, but only when the world was what stopped it. Three of the four
 * guards are about not claiming it: the caller's domain, the edge of a captured window and the
 * body's breath each exhaust the open set while saying nothing about the terrain.
 *
 * <p>{@code AsciiWorld} keeps {@link NavGrid#inBounds}'s default, so its edges are real walls
 * — which is why the window case is built from a wrapper that behaves like a snapshot.
 */
class PathfinderSealedTest {

    /** A biped that will not put its head under — the cautious default (see MoveCapabilities). */
    private static final MoveCapabilities HOLDS_ITS_BREATH =
            new MoveCapabilities(1.8, 1, 3, 3, true, 0);

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return find(world, sx, sy, sz, gx, gy, gz, TestBodies.BIPED);
    }

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz,
                             MoveCapabilities body) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, body));
    }

    // ── the proof ────────────────────────────────────────────────────────────────────────────

    @Test
    void aBodyWalledIntoOneCellIsSealed() {
        Path path = find(AsciiWorld.of(
                "###",
                "#1#",
                "###"), 1, 1, 1, 20, 1, 20);
        assertFalse(path.reachedGoal());
        assertTrue(path.sealed(), "nothing but walls stopped the search");
        assertEquals(1, path.reachableCells(), "the cell it stands on, and nowhere else");
    }

    @Test
    void theCellCountIsTheWholeReachableRegion() {
        Path path = find(AsciiWorld.of(
                "#####",
                "#111#",
                "#111#",
                "#111#",
                "#####"), 2, 1, 2, 20, 1, 20);
        assertTrue(path.sealed());
        assertEquals(9, path.reachableCells());
    }

    /** A search that arrived never ran out of anywhere to go, so it never claims this. */
    @Test
    void aPathThatReachesItsGoalIsNeverSealed() {
        Path path = find(AsciiWorld.of("11111"), 0, 1, 0, 4, 1, 0);
        assertTrue(path.reachedGoal());
        assertFalse(path.sealed());
    }

    // ── the three ways to exhaust a search without proving anything ───────────────────────────

    /**
     * Out of budget is not out of world — which is why the flag is set explicitly rather than
     * read off an empty open set at the end.
     */
    @Test
    void aBudgetLimitedSearchIsNeverSealed() {
        AsciiWorld room = AsciiWorld.of(
                "#####",
                "#111#",
                "#111#",
                "#111#",
                "#####");
        assertTrue(find(room, 2, 1, 2, 20, 1, 20).sealed(), "with room to finish, it is a proof");

        PathRequest pinched = new PathRequest(2, 1, 2, 20, 1, 20, TestBodies.BIPED,
                null, null, 4, 0L, null);
        Path path = Pathfinder.find(room, pinched);
        assertFalse(path.sealed(), "it stopped counting, it did not run out of world");
    }

    /**
     * A {@link NavDomain} is a fence the CALLER put up (the chop keeps a Person on her own tree);
     * exhausting inside it says nothing about whether she could walk off it.
     */
    @Test
    void aFencedRequestIsNeverSealed() {
        AsciiWorld open = AsciiWorld.of(
                "11111",
                "11111",
                "11111");
        NavDomain pen = NavDomain.of(List.of(new Pos(1, 1, 1), new Pos(2, 1, 1)));
        Path path = Pathfinder.find(open,
                PathRequest.of(1, 1, 1, 4, 1, 2, TestBodies.BIPED).within(pen));
        assertFalse(path.reachedGoal());
        assertFalse(path.sealed(), "the fence was ours, not the world's");
    }

    /**
     * Everything past a snapshot's edge reads OBSTACLE, so a region that reaches the edge of what
     * was captured makes no claim at all.
     */
    @Test
    void aRegionThatReachesTheEdgeOfTheCaptureIsNeverSealed() {
        AsciiWorld ground = AsciiWorld.of(
                "11111",
                "11111",
                "11111");
        Path path = find(window(ground, 0, -4, 0, 4, 4, 2), 2, 1, 1, 40, 1, 40);
        assertFalse(path.reachedGoal());
        assertFalse(path.sealed(), "the wall was the edge of the capture");
    }

    /**
     * Pairs with the test above: the same terrain, captured wide enough that the enclosing walls
     * are drawn ones rather than the window's edge.
     */
    @Test
    void theSameRegionInsideAWideEnoughWindowIsSealed() {
        AsciiWorld room = AsciiWorld.of(
                "#####",
                "#111#",
                "#111#",
                "#111#",
                "#####");
        Path path = find(window(room, -40, -40, -40, 40, 40, 40), 2, 1, 2, 80, 1, 80);
        assertTrue(path.sealed(), "the capture is nowhere near; the walls are real");
    }

    /**
     * A route refused for want of breath is a limit of the lungs, not of the rock: a body that
     * will not dive cannot say what is under the water.
     */
    @Test
    void aSearchFencedByItsOwnBreathIsNeverSealed() {
        AsciiWorld pool = AsciiWorld.of(
                "#####",
                "#1W1#",
                "#####");
        assertFalse(find(pool, 1, 1, 1, 20, 1, 20, HOLDS_ITS_BREATH).sealed(),
                "it never looked under the water; it cannot claim there is no way out down there");
        assertTrue(find(pool, 1, 1, 1, 20, 1, 20, TestBodies.BIPED).sealed(),
                "with air to spend it searched the pool too, and the pool is a dead end");
    }

    /**
     * A grid that behaves like a {@code WorldSnapshot}: past the captured box, {@code cell} reads
     * OBSTACLE and {@code inBounds} says so. Kept out of the fixtures because a fixture that
     * blurred that distinction would be the bug.
     */
    private static NavGrid window(NavGrid inner, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ) {
        return new NavGrid() {
            @Override
            public CellType cell(int x, int y, int z) {
                return inBounds(x, y, z) ? inner.cell(x, y, z) : CellType.OBSTACLE;
            }

            @Override
            public double surface(int x, int y, int z) {
                return inBounds(x, y, z) ? inner.surface(x, y, z) : 0.0;
            }

            @Override
            public boolean inBounds(int x, int y, int z) {
                return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
            }
        };
    }
}
