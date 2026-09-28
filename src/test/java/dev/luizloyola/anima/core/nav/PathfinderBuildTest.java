package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Decks and pillars in the route search (docs/superpowers/specs/2026-09-28-bridging-design.md):
 * found when a body may build and they beat the way round, and never where the spec's safety
 * lines say no.
 *
 * <p>Every map is {@link AsciiWorld#bounded}: a drawn map reads OBSTACLE past its rows, and a
 * pillar would lean on that as a wall. A capture's edge is out of bounds, and so is this one's.
 */
class PathfinderBuildTest {

    private static final MoveCapabilities EMPTY_HANDED = TestBodies.BIPED;

    private static MoveCapabilities carrying(int blocks) {
        return TestBodies.BIPED.withLaid(blocks);
    }

    /** The map drawn by {@code rows}, its edge the edge of the world. */
    private static NavGrid bounded(String... rows) {
        return AsciiWorld.of(rows).bounded();
    }

    private static Path find(NavGrid grid, int sx, int sy, int sz, int gx, int gy, int gz,
                             MoveCapabilities body) {
        return Pathfinder.find(grid, PathRequest.of(sx, sy, sz, gx, gy, gz, body));
    }

    private static long count(Path path, MoveType move) {
        return path.waypoints().stream().filter(w -> w.move() == move).count();
    }

    /** Every edge of the route is one the world it was planned in provides, lays included. */
    private static void assertWalkable(NavGrid grid, Path path, int sx, int sy, int sz,
                                       MoveCapabilities body) {
        Waypoint previous = new Waypoint(sx, sy, sz, MoveType.WALK);
        for (Waypoint to : path.waypoints()) {
            for (CellNeed need : PathIntegrity.edgeNeeds(previous, to, body)) {
                assertTrue(NavGrids.satisfies(grid, need),
                        () -> "the leg into " + to + " needs " + need + ": " + path.waypoints());
            }
            previous = to;
        }
    }

    // ── a gap, and the way round ─────────────────────────────────────────────────────────────

    /** Four wide, with the way round nineteen rows down. */
    private static NavGrid longWayRound() {
        String[] rows = new String[21];
        for (int z = 0; z < 20; z++) {
            rows[z] = "1111    1111";
        }
        rows[20] = "111111111111";
        return bounded(rows);
    }

    @Test
    void aGapWithALongWayRoundIsBridged() {
        NavGrid grid = longWayRound();
        Path path = find(grid, 1, 1, 1, 10, 1, 1, carrying(16));
        assertTrue(path.reachedGoal());
        assertEquals(4, path.laid());
        assertEquals(4, count(path, MoveType.BRIDGE));
        assertTrue(path.waypoints().stream().filter(w -> w.move().lays()).allMatch(w -> w.y() == 1),
                "decks are level: " + path.waypoints());
        assertWalkable(grid, path, 1, 1, 1, carrying(16));
    }

    @Test
    void aGapWithAShortWayRoundIsWalkedRound() {
        String[] rows = {"1111    1111", "1111    1111", "111111111111", "1111    1111"};
        Path path = find(bounded(rows), 1, 1, 1, 10, 1, 1, carrying(16));
        assertTrue(path.reachedGoal());
        assertEquals(0, path.laid(), "a bridge must not beat a short way round");
    }

    @Test
    void anEmptyPocketWalksTheLongWayRound() {
        Path path = find(longWayRound(), 1, 1, 1, 10, 1, 1, EMPTY_HANDED);
        assertTrue(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    // ── what the pocket and the caps allow ───────────────────────────────────────────────────

    private static NavGrid gap(int width) {
        String row = "111" + " ".repeat(width) + "111";
        return bounded(row, row, row);
    }

    @Test
    void fiveBlocksDoNotBridgeSix() {
        Path path = find(gap(6), 1, 1, 1, 10, 1, 1, carrying(5));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid(), "a building route that does not arrive is thrown away");
    }

    @Test
    void sixBlocksDo() {
        Path path = find(gap(6), 1, 1, 1, 10, 1, 1, carrying(6));
        assertTrue(path.reachedGoal());
        assertEquals(6, path.laid());
    }

    @Test
    void theSpanCapHolds() {
        assertTrue(find(gap(16), 1, 1, 1, 20, 1, 1, carrying(32)).reachedGoal());
        Path path = find(gap(17), 1, 1, 1, 21, 1, 1, carrying(32));
        assertFalse(path.reachedGoal(), "past the span a gap is the builder's");
        assertEquals(0, path.laid());
    }

    // ── goals nothing may be built toward ────────────────────────────────────────────────────

    @Test
    void aGoalInTheAirIsNeverBuiltToward() {
        Path path = find(gap(6), 1, 1, 1, 5, 1, 1, carrying(16));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    @Test
    void aGoalOverLavaIsNeverBuiltToward() {
        String row = "111LLL111";
        Path path = find(bounded(row, row, row), 1, 1, 1, 4, 1, 1, carrying(16));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    @Test
    void aGoalOutsideTheGridIsNeverBuiltToward() {
        Path path = find(gap(6), 1, 1, 1, 40, 1, 1, carrying(16));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    // ── pillars ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aPillarClimbsTheWallOfAPit() {
        NavGrid grid = bounded(
                "55555",
                "51115",
                "51115",
                "51115",
                "55555");
        assertFalse(find(grid, 2, 1, 2, 4, 5, 2, EMPTY_HANDED).reachedGoal());
        Path path = find(grid, 2, 1, 2, 4, 5, 2, carrying(8));
        assertTrue(path.reachedGoal());
        // Four up is three blocks and a jump: the last block of rise is the body's own.
        assertEquals(3, count(path, MoveType.PILLAR));
        assertEquals(3, path.laid());
        assertWalkable(grid, path, 2, 1, 2, carrying(8));
    }

    @Test
    void aPillarClimbsACrevice() {
        NavGrid grid = bounded(
                "555",
                "515",
                "555");
        Path path = find(grid, 1, 1, 1, 2, 5, 1, carrying(8));
        assertTrue(path.reachedGoal());
        assertEquals(3, count(path, MoveType.PILLAR));
    }

    @Test
    void thePillarCapHolds() {
        assertTrue(find(bounded("999", "919", "999"), 1, 1, 1, 2, 9, 1, carrying(32)).reachedGoal());
        // Twenty up, deeper than a digit draws: walls filled in round a one-cell shaft.
        String[] rows = {"111", "111", "111"};
        AsciiWorld deep = AsciiWorld.of(rows)
                .fill(0, 1, 0, 2, 20, 0, CellType.GROUND)
                .fill(0, 1, 2, 2, 20, 2, CellType.GROUND)
                .fill(0, 1, 1, 0, 20, 1, CellType.GROUND)
                .fill(2, 1, 1, 2, 20, 1, CellType.GROUND);
        Path path = find(deep.bounded(), 1, 1, 1, 2, 21, 1, carrying(32));
        assertFalse(path.reachedGoal(), "past the cap a shaft is not climbed by stacking");
    }

    /** Two slabs stacked are a step of one and a half: too high to jump, not too smooth to lean on. */
    @Test
    void aPillarLeansOnASlabStack() {
        String[] rows = {"1111111", "1111111", "1111111"};
        AsciiWorld world = AsciiWorld.of(rows)
                .step(3, 1, 0, 3, 1, 2, 0.5)
                .step(3, 2, 0, 3, 2, 2, 0.5);
        assertFalse(find(world.bounded(), 1, 1, 1, 5, 1, 1, EMPTY_HANDED).reachedGoal());
        Path path = find(world.bounded(), 1, 1, 1, 5, 1, 1, carrying(4));
        assertTrue(path.reachedGoal());
        assertEquals(1, count(path, MoveType.PILLAR));
    }

    /** A platform floating over open ground has no wall under its edge to lean a pillar on. */
    @Test
    void noTowerIsBuiltInTheOpen() {
        String[] rows = {"1111111", "1111111", "1111111", "1111111", "1111111"};
        AsciiWorld world = AsciiWorld.of(rows).fill(3, 4, 2, 3, 4, 2, CellType.GROUND);
        Path path = find(world.bounded(), 0, 1, 2, 3, 5, 2, carrying(16));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    @Test
    void aLedgeAcrossAGapTakesDecksThenAPillar() {
        String row = "111   333";
        NavGrid grid = bounded(row, row, row);
        assertFalse(find(grid, 1, 1, 1, 7, 3, 1, EMPTY_HANDED).reachedGoal(),
                "a leap lands level or not at all");
        Path path = find(grid, 1, 1, 1, 7, 3, 1, carrying(8));
        assertTrue(path.reachedGoal());
        assertEquals(3, count(path, MoveType.BRIDGE));
        assertEquals(1, count(path, MoveType.PILLAR));
        assertWalkable(grid, path, 1, 1, 1, carrying(8));
    }

    // ── what is never laid ───────────────────────────────────────────────────────────────────

    @Test
    void noDeckIsLaidOverLava() {
        String open = "555    555";
        String lava = "555LLLL555";
        assertTrue(find(bounded(open, open, open), 1, 5, 1, 8, 5, 1, carrying(16)).reachedGoal(),
                "the control: over air the same gap is bridged");
        Path path = find(bounded(lava, lava, lava), 1, 5, 1, 8, 5, 1, carrying(16));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    @Test
    void aDeckIsLaidFromASlabOnABlock() {
        String[] rows = {"111    111", "111    111", "111    111"};
        AsciiWorld world = AsciiWorld.of(rows).step(2, 1, 0, 2, 1, 2, 0.5);
        assertTrue(find(world.bounded(), 1, 1, 1, 8, 1, 1, carrying(16)).reachedGoal(),
                "the block under the slab is what the deck is laid against");
    }

    @Test
    void noDeckIsLaidFromASlabOverAir() {
        String[] rows = {"11     111", "11     111", "11     111"};
        AsciiWorld world = AsciiWorld.of(rows).step(2, 1, 0, 2, 1, 2, 0.5);
        assertFalse(find(world.bounded(), 0, 1, 1, 8, 1, 1, carrying(16)).reachedGoal(),
                "a slab with nothing under it has no side to lay against");
    }

    @Test
    void aCellHoldingSomethingIsNeverLaidInto() {
        String[] rows = {"111    111", "111    111", "111    111"};
        AsciiWorld world = AsciiWorld.of(rows).fixed(4, 0, 0, 4, 0, 2);
        Path path = find(world.bounded(), 1, 1, 1, 8, 1, 1, carrying(16));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid());
    }

    @Test
    void nothingLeapsOffADeck() {
        Path path = find(gap(7), 1, 1, 1, 11, 1, 1, carrying(16));
        assertTrue(path.reachedGoal());
        assertEquals(7, path.laid(), "four decks and a leap would be cheaper, and is not allowed");
        assertTrue(path.waypoints().stream()
                .noneMatch(w -> w.move() == MoveType.LEAP || w.move() == MoveType.RUNUP));
    }

    // ── a recorded pillar ────────────────────────────────────────────────────────────────────

    /** A pit four deep with a pillar standing in its north-west corner, its top level with the rim. */
    private static AsciiWorld pitWithAPillar() {
        return AsciiWorld.of(
                "55555",
                "51115",
                "51115",
                "51115",
                "55555").fill(1, 1, 1, 1, 4, 1, CellType.GROUND);
    }

    private static java.util.Set<Long> thePillar() {
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (int y = 1; y <= 4; y++) {
            cells.add(LaidBlocks.cell(1, y, 1));
        }
        return cells;
    }

    private static final MoveCapabilities SCALER = TestBodies.BIPED.withScaling(true);

    @Test
    void anEmptyPocketClimbsBesideARecordedPillar() {
        NavGrid grid = pitWithAPillar().bounded();
        assertFalse(Pathfinder.find(grid, PathRequest.of(2, 1, 1, 2, 5, 0, SCALER)).reachedGoal(),
                "an unrecorded column is somebody's, not a ladder");
        Path path = Pathfinder.find(grid, PathRequest.of(2, 1, 1, 2, 5, 0, SCALER).near(thePillar()));
        assertTrue(path.reachedGoal());
        assertEquals(3, count(path, MoveType.PILLAR), "three blocks off the pillar, then a jump");
        assertWalkable(grid, path, 2, 1, 1, SCALER);
    }

    @Test
    void aRecordedPillarIsGoneDown() {
        NavGrid grid = pitWithAPillar().bounded();
        assertFalse(Pathfinder.find(grid, PathRequest.of(0, 5, 1, 3, 1, 3, SCALER)).reachedGoal(),
                "four down is a fall this body will not take");
        Path path = Pathfinder.find(grid, PathRequest.of(0, 5, 1, 3, 1, 3, SCALER).near(thePillar()));
        assertTrue(path.reachedGoal());
        assertEquals(4, count(path, MoveType.LOWER), () -> "every block of it, eaten on the way down: " + path.waypoints());
        assertWalkable(grid, path, 0, 5, 1, SCALER);
    }

    @Test
    void aWalkThatMayNotScaleLeavesAPillarAlone() {
        NavGrid grid = pitWithAPillar().bounded();
        assertFalse(Pathfinder.find(grid, PathRequest.of(0, 5, 1, 3, 1, 3, TestBodies.BIPED)
                .near(thePillar())).reachedGoal());
    }

    // ── who never builds ─────────────────────────────────────────────────────────────────────

    /**
     * Unbounded: a survey never proves a prison against the edge of a capture, so this pit's walls
     * have to be the world's.
     */
    @Test
    void aSurveyOfAPitStaysSealedWithBlocksInHand() {
        AsciiWorld pit = AsciiWorld.of("555", "515", "555");
        assertTrue(Pathfinder.find(pit, PathRequest.of(1, 1, 1, 2, 5, 1, carrying(16))).reachedGoal(),
                "the body could build its way out");
        assertTrue(Pathfinder.survey(pit, PathRequest.of(1, 1, 1, 0, 0, 0, carrying(16))).sealed(),
                "a survey asks whether the body can walk out, not whether it could build out");
    }

    @Test
    void theBalanceNeverGoesBelowZero() {
        for (int blocks = 0; blocks <= 8; blocks++) {
            Path path = find(gap(6), 1, 1, 1, 10, 1, 1, carrying(blocks));
            assertTrue(path.laid() <= blocks, blocks + " blocks laid " + path.laid());
        }
    }
}
