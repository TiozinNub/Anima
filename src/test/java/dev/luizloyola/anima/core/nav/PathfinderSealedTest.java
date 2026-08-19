package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Confinement;
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

    // ── asking on purpose ────────────────────────────────────────────────────────────────────

    /**
     * No goal involved. Inferring the verdict from the last walk a body attempted fails in exactly
     * the case it is for: a body cutting its way out asks only for cells inside its own prison,
     * and every one of those routes succeeds.
     */
    @Test
    void theSurveyProvesConfinementWithoutBeingGivenAGoal() {
        AsciiWorld room = AsciiWorld.of(
                "#####",
                "#111#",
                "#111#",
                "#111#",
                "#####");
        Confinement verdict = Pathfinder.survey(room,
                PathRequest.of(2, 1, 2, 2, 1, 2, TestBodies.BIPED));
        assertTrue(verdict.sealed());
        assertEquals(9, verdict.cells());
    }

    /** The same capture guard the routing verdict uses, reached by the same pairing. */
    @Test
    void theSurveyClaimsNothingWhenTheGroundRunsPastTheCapture() {
        AsciiWorld ground = AsciiWorld.of(
                "11111",
                "11111",
                "11111");
        Confinement verdict = Pathfinder.survey(window(ground, 0, -4, 0, 4, 4, 2),
                PathRequest.of(2, 1, 1, 2, 1, 1, TestBodies.BIPED));
        assertFalse(verdict.sealed(), "the wall was the edge of the capture");
    }

    /** A cell the body has just opened is genuinely new ground, and must still read "shut in". */
    @Test
    void movingWithinThePrisonDoesNotReadAsGettingOut() {
        AsciiWorld room = AsciiWorld.of(
                "#####",
                "#111#",
                "#111#",
                "#111#",
                "#####");
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                assertTrue(Pathfinder.survey(room,
                                PathRequest.of(x, 1, z, x, 1, z, TestBodies.BIPED)).sealed(),
                        "still shut in, standing at (" + x + ", 1, " + z + ")");
            }
        }
    }

    // ── the cost of proving the NEGATIVE ─────────────────────────────────────────────────────

    /**
     * A body standing in the open pays to enumerate its whole capture box in order to learn what
     * the first cell at the rim already settled.
     *
     * <p>"Not sealed" is MONOTONE over the closed set: {@code sealedIn} fails the verdict as soon
     * as one closed cell sits within {@code margin} of the capture edge, and the closed set only
     * ever grows, so no later expansion can restore the claim. Every expansion after the first
     * such cell is work that cannot change the answer — and in the open that is the common case,
     * run per body per second on the server thread.
     *
     * <p>The box here is the one {@code PathfinderService.surveyFrom} builds: HORIZONTAL_MARGIN
     * either side of the body, DOWN_MARGIN under it and UP_MARGIN over it.
     */
    @Test
    void theSurveyStopsOnceTheRegionHasTouchedTheRim() {
        Confinement verdict = Pathfinder.survey(surveyBox(flatGround()),
                PathRequest.of(0, 1, 0, 0, 1, 0, TestBodies.BIPED));

        assertFalse(verdict.sealed(), "open ground inside a window claims nothing");
        // This box holds 21x21 = 441 standable cells and the survey used to close every one of a
        // 33x33 one. The rim is ~6 out, so the wavefront settles it inside ~150. Pinned loosely:
        // this guards the box-enumerating regression, not the exact shape of the wavefront.
        assertTrue(verdict.cells() < 200,
                "settled at the rim, not by enumerating the box; expanded " + verdict.cells());
    }

    /**
     * The size of the capture is the size of the claim. A room that fits clear of the rim is still
     * proved a prison — this is the case {@code SURVEY_MARGIN} is chosen to keep.
     */
    @Test
    void anEnclosureThatFitsInsideTheSurveyBoxIsStillProvedAPrison() {
        Confinement verdict = Pathfinder.survey(surveyBox(walledRoom(5)),
                PathRequest.of(0, 1, 0, 0, 1, 0, TestBodies.BIPED));

        assertTrue(verdict.sealed(), "11x11 of floor, walled, well inside the capture");
        assertEquals(121, verdict.cells(), "the whole room, and nothing outside it");
    }

    /**
     * The other side of that trade, stated rather than discovered: a wider room reaches the rim,
     * so the survey declines to call it a prison even though its walls are real.
     */
    @Test
    void anEnclosureWiderThanTheSurveyBoxCanProveReadsOpen() {
        Confinement verdict = Pathfinder.survey(surveyBox(walledRoom(6)),
                PathRequest.of(0, 1, 0, 0, 1, 0, TestBodies.BIPED));

        assertFalse(verdict.sealed(), "13x13 touches the rim; the claim would be about the capture");
    }

    /** The box {@code PathfinderService.surveyFrom} builds: SURVEY_MARGIN out, DOWN/UP under and over. */
    private static NavGrid surveyBox(NavGrid inner) {
        return window(inner, -10, -9, -10, 10, 7, 10);
    }

    /** Flat ground fenced by REAL walls at {@code ±half} — a room, not the edge of a capture. */
    private static NavGrid walledRoom(int half) {
        return (x, y, z) -> Math.abs(x) > half || Math.abs(z) > half
                ? CellType.OBSTACLE
                : (y <= 0 ? CellType.GROUND : CellType.PASSABLE);
    }

    /** The proof still has to be a proof: stopping early must never invent a sealed verdict. */
    @Test
    void stoppingEarlyNeverTurnsOpenGroundIntoAPrison() {
        for (int dx = -4; dx <= 4; dx += 2) {
            for (int dz = -4; dz <= 4; dz += 2) {
                Confinement verdict = Pathfinder.survey(surveyBox(flatGround()),
                        PathRequest.of(dx, 1, dz, dx, 1, dz, TestBodies.BIPED));
                assertFalse(verdict.sealed(),
                        "open ground at (" + dx + ", 1, " + dz + ")");
            }
        }
    }

    /** Ground with nothing under the horizon — what a settler stands on away from any wall. */
    private static NavGrid flatGround() {
        return (x, y, z) -> y <= 0 ? CellType.GROUND : CellType.PASSABLE;
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
