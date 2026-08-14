package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The nav gauntlet, replayed headlessly: for every station of the in-world course, does the planner
 * produce a path to the goal at all? Whether the SEARCH can represent a move is pure {@code core/}
 * arithmetic over a {@link NavGrid} — deterministic, microseconds, no game; whether the FOLLOWER
 * can execute it needs a ticking world. Only the first is answered here, over a region
 * {@code /anima nav dump} captured out of the live course, so <em>never even tries</em> is this
 * tier and <em>tries and fails</em> / <em>succeeds</em> are the in-world run.
 *
 * <p>A capture, not a drawn {@link AsciiWorld} map ({@code PathfinderTest} draws the terrain it
 * makes a claim about): half of what this course asks is whether the CLASSIFIER understands a real
 * blockstate, and drawing a staircase as ground assumes the answer. The capture carries whatever
 * {@code WorldSnapshot} actually said.
 *
 * <p>The {@code plans} column is a lock, not a prediction — what the planner does today, measured,
 * so adding diagonal jumps should flip the stations about diagonal jumps. Nothing asserts
 * the current answer is the right one; several are known gaps, named in the station titles.
 */
class GauntletPathTest {

    /** The body every expectation was recorded for — the Person's declared capabilities. */
    private static final MoveCapabilities BODY = TestBodies.BIPED;

    private record Station(String id, int sx, int sy, int sz, int gx, int gy, int gz,
                           String plans, String title) {
    }

    private static CapturedWorld world;
    private static List<Station> stations;

    @BeforeAll
    static void load() {
        world = CapturedWorld.parse(CapturedWorld.lines(resource("/nav/gauntlet.txt")));
        stations = readStations();
    }

    private static InputStream resource(String path) {
        InputStream in = GauntletPathTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("missing test resource: " + path);
        }
        return in;
    }

    private static List<Station> readStations() {
        List<Station> out = new ArrayList<>();
        for (String line : CapturedWorld.lines(resource("/nav/gauntlet-stations.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            out.add(new Station(f[0],
                    Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]),
                    f[7], f.length > 9 ? f[9] : ""));
        }
        return out;
    }

    static Stream<Station> stations() {
        return stations.stream();
    }

    private static boolean plans(Station s) {
        return Pathfinder.find(world,
                PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).reachedGoal();
    }

    /**
     * A handful of {@link PathRequest#varying} seeds — one per imaginary settler. Variety is an
     * opinion about what ground costs to cross and must never amount to a capability: Which body
     * is asking cannot decide whether a place can be reached. 186 real stations, half of them
     * one-way-through by construction, is where that claim can be made at all.
     */
    private static final long[] VARIETIES = {1L, 6_364_136_223_846_793_005L, -42L, 8_675_309L};

    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void plannerVerdictIsTheSameForEverySettler(Station s) {
        boolean canonical = plans(s);
        for (long variety : VARIETIES) {
            boolean seeded = Pathfinder.find(world,
                    PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)
                            .varying(variety)).reachedGoal();
            assertEquals(canonical, seeded,
                    () -> s.id() + " (" + s.title() + "): seed " + variety + " disagrees with the "
                            + "canonical search about whether this station can be reached. A "
                            + "variety seed bends a route — it must not decide there is one.");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void plannerVerdictIsUnchanged(Station s) {
        assertEquals(Boolean.parseBoolean(s.plans()), plans(s),
                () -> s.id() + " (" + s.title() + "): the planner changed its mind about whether "
                        + "it can reach " + s.gx() + " " + s.gy() + " " + s.gz()
                        + ". If that was the point of your change, re-record the row.");
    }

    /**
     * Every endpoint has to be inside the captured box, because outside it every cell reads
     * {@link CellType#OBSTACLE} — a station whose goal fell off the edge would "fail to plan" for
     * a reason that has nothing to do with navigation, and would look exactly like a finding.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void endpointsAreInsideTheCapture(Station s) {
        assertTrue(world.covers(s.sx(), s.sy(), s.sz(), s.sx(), s.sy(), s.sz()),
                () -> s.id() + ": start is outside the capture (" + world.bounds() + ")");
        assertTrue(world.covers(s.gx(), s.gy(), s.gz(), s.gx(), s.gy(), s.gz()),
                () -> s.id() + ": goal is outside the capture (" + world.bounds() + ")");
    }

    /**
     * A station that starts somewhere it cannot stand tests nothing — it would refuse to plan no
     * matter what the obstacle is. Cheap guard against a mis-sited pad silently reading as a gap
     * in the move model.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void startIsStandable(Station s) {
        assertEquals(CellType.GROUND, world.cell(s.sx(), s.sy() - 1, s.sz()),
                () -> s.id() + ": nothing solid under the start pad");
    }

    /**
     * Every route must be one a body could walk in the world it was planned in: each edge goes back
     * through {@link PathIntegrity}, the engine's own statement of what that edge depends on, and
     * the captured world is asked whether it provides it. A path that fails this was malformed on
     * the day it was made, not "walkable until something changes" — that is the follower's problem.
     *
     * <p>Everything else here asks only whether a route EXISTS and is blind to what it is made of:
     * A9 once scored a clean {@code plans=true} while laying a RUNUP over a six-block hole.
     *
     * <p>It reuses the searches the lock already runs, so any future move that emits an edge its own
     * integrity rule refuses trips on 186 real terrains.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void everyPlannedRouteIsWalkableInTheWorldItWasPlannedIn(Station s) {
        List<Waypoint> route = Pathfinder.find(world,
                PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).waypoints();
        // The body's own cell is where the first edge comes from. Its surface is zero by the same
        // reasoning startIsStandable pins: a station pad is a full block.
        Waypoint previous = new Waypoint(s.sx(), s.sy(), s.sz(), MoveType.WALK);
        for (Waypoint to : route) {
            Waypoint from = previous;
            for (CellNeed need : PathIntegrity.edgeNeeds(from, to, BODY)) {
                assertTrue(NavGrids.satisfies(world, need),
                        () -> s.id() + " (" + s.title() + "): the route asks for " + need.need()
                                + " at " + need.x() + " " + need.y() + " " + need.z()
                                + ", which the captured world does not provide — the edge into "
                                + to.move() + " " + to.x() + " " + to.y() + " " + to.z()
                                + " is not walkable. Full route: " + route);
            }
            previous = to;
        }
    }

    /**
     * The rim starts (see {@code rim_start} in the generator): stations that begin ON the takeoff
     * rather than on a pad, which is the one thing the other 186 cannot ask. Reaching the goal is
     * not enough here, so this asserts HOW.
     *
     * <p>A wide gap must be crossed by first walking AWAY from it — a body on the takeoff has half
     * a block of runway, so the route goes back a cell and the step onto the takeoff comes back
     * marked {@link MoveType#RUNUP}. A 1-cell hop must not: that cell is waste, and it is the half
     * of the rule most easily lost by tuning the other.
     */
    @Test
    void rimStartsBackUpForAWideGapAndOnlyForAWideGap() {
        for (String id : List.of("A1.R", "A2.R", "A3.R")) {
            Station s = stations.stream().filter(st -> st.id().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no station " + id));
            List<Waypoint> route = Pathfinder.find(world,
                    PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).waypoints();
            boolean backsUp = route.stream().anyMatch(w -> w.move() == MoveType.RUNUP);
            if (id.equals("A1.R")) {
                assertFalse(backsUp,
                        id + " (" + s.title() + ") spent a cell backing up for a hop: " + route);
                continue;
            }
            assertTrue(backsUp,
                    id + " (" + s.title() + ") crossed a wide gap with no run-up — from the "
                            + "takeoff that is a standing jump, whatever the plan says: " + route);
            assertTrue(route.get(0).x() < s.sx(),
                    id + " (" + s.title() + "): the run-up has to be walked BACKWARDS from the "
                            + "start, and the first step goes forward: " + route);
        }
    }

    /**
     * The rows each serpentine has to actually set foot on. A lane five cells wide between two pads
     * that each span the full width hands out a shortcut the moment any single row runs from one
     * pad to the other inside leap range.
     *
     * <p>A12 gave one out twice — a leap due east off the start pad at zc+1 onto the row-+1 pillar,
     * a gap of 3, exactly maxLeap, skipping all of row -2 and the first turn. Both leaks are walled
     * off; this is the alarm for the next redraw that opens one.
     *
     * <p>Rows, not a count of turns: the shortcut route still changed axis six times,
     * so counting corners would have waved it through. What a shortcut always does is leave a row
     * out.
     */
    private static final Map<String, int[]> SERPENTINE_ROWS = Map.of(
            "A12", new int[]{134, 135, 136, 137, 138},
            "A13", new int[]{146, 147, 150});

    @Test
    void serpentinesAreNotQuietlySolvedByLeavingARowOut() {
        for (Map.Entry<String, int[]> entry : SERPENTINE_ROWS.entrySet()) {
            String id = entry.getKey();
            Station s = stations.stream().filter(st -> st.id().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no station " + id));
            List<Waypoint> route = Pathfinder.find(world,
                    PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).waypoints();
            for (int row : entry.getValue()) {
                assertTrue(route.stream().anyMatch(w -> w.z() == row),
                        () -> id + " (" + s.title() + ") reaches the goal without ever standing on "
                                + "row z=" + row + ", so it is being solved by a shortcut and is "
                                + "measuring an easier lane than the one it is named for: " + route);
            }
        }
    }

    /**
     * Stations whose whole subject is the diagonal: a route that uses a LEAP is not measuring what
     * its title says. J13's zigzag turns back on itself, so every level below a peak held two floor
     * cells two apart and a body hopped those 1-gaps for 2.4 rather than walking two diagonals for
     * 6.22 — a whole run reported a chained-LEAP failure under a chained-diagonal name.
     *
     * <p>Two defences: A8's rule (use each z once, so no cardinal leap runs down the line), which a
     * zigzag cannot obey, and J13's roof — a leap needs a body-height+1 corridor for its arc, and a
     * ceiling two cells up denies it while a 1.8 body walks under untroubled.
     *
     * <p>A8 and C7 are absent: their routes take the diagonals and then leap a last
     * leapable stretch, so demanding no leap anywhere over-specifies somebody else's lane. These
     * four carry the claim in their titles.
     */
    private static final List<String> DIAGONAL_ONLY = List.of("J1", "J2", "J4", "J13");

    @Test
    void diagonalStationsAreNotQuietlySolvedByLeaping() {
        for (String id : DIAGONAL_ONLY) {
            Station s = stations.stream().filter(st -> st.id().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no station " + id));
            // Not `Path path = …`: this file imports java.nio.file.Path for the report writer,
            // which shadows the one the search returns.
            List<Waypoint> route = Pathfinder.find(world,
                    PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).waypoints();
            assertTrue(route.stream().noneMatch(w -> w.move() == MoveType.LEAP),
                    id + " (" + s.title() + ") is solved by leaping, so it measures leaps rather "
                            + "than diagonals: " + route);
        }
    }

    /**
     * Stations whose whole subject is the water. Same disease as {@link #DIAGONAL_ONLY}: H6 read a
     * clean pass for a route that walked the length of its pool's RETAINING WALL and dropped onto
     * the goal pad without getting wet.
     *
     * <p>The assertion is that the body ends a leg standing IN a water cell, not merely that the
     * route contains a {@link MoveType#SWIM} — the move is what the planner calls it, the cell is
     * what the world says, and it was the world that was wrong. A surface swimmer's feet cell is
     * the water cell, so this is a direct reading.
     *
     * <p>E3 is absent: a two-wide channel is meant to be LEAPT (see
     * {@code PathfinderSwimTest.stillLeapsNarrowWaterRatherThanSwim}). E5 and E8 are absent because
     * nothing plans them yet; add each one here as its rung lands.
     */
    private static final List<String> MUST_GET_WET =
            List.of("E1", "E2", "E4", "E6", "E7", "H6");

    /**
     * E6's only way through is under, so a route that never puts the body's head below the surface
     * is not an answer however wet it gets. The station is roofed for that reason: an earlier cut
     * stopped the lid at the waterline and was strolled over.
     */
    @Test
    void theUnderwaterTunnelIsActuallySwumUnder() {
        Station s = stations.stream().filter(st -> st.id().equals("E6")).findFirst().orElseThrow();
        List<Waypoint> route = Pathfinder.find(world,
                PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).waypoints();
        assertTrue(route.stream().anyMatch(w -> w.move() == MoveType.DIVE),
                "E6 is reached without ever going under: " + route);
    }

    @Test
    void waterStationsAreNotQuietlySolvedOnDryLand() {
        for (String id : MUST_GET_WET) {
            Station s = stations.stream().filter(st -> st.id().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no station " + id));
            List<Waypoint> route = Pathfinder.find(world,
                    PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), BODY)).waypoints();
            assertTrue(route.stream().anyMatch(w -> world.cell(w.x(), w.y(), w.z()) == CellType.WATER),
                    id + " (" + s.title() + ") is reached without ever standing in water, so it "
                            + "measures something other than what its name says: " + route);
        }
    }

    /**
     * Writes the measured table to {@code build/gauntlet-plans.tsv} — a course has to be readable
     * as a course, not as one line per failed assertion. Also how the {@code plans} column gets
     * (re-)recorded after a deliberate change.
     */
    @Test
    void report() throws IOException {
        StringBuilder out = new StringBuilder("# id\tplans\trecorded\ttitle\n");
        int agree = 0;
        for (Station s : stations) {
            boolean actual = plans(s);
            if (String.valueOf(actual).equals(s.plans())) {
                agree++;
            }
            out.append(s.id()).append('\t').append(actual).append('\t')
                    .append(s.plans()).append('\t').append(s.title()).append('\n');
        }
        Path file = Path.of("build", "gauntlet-plans.tsv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        System.out.println("gauntlet: " + agree + "/" + stations.size()
                + " stations match their recorded verdict -> " + file.toAbsolutePath());
    }

    /** A row nobody recorded is a row nobody looked at; an all-{@code ?} table would pass. */
    @Test
    void everyStationIsRecorded() {
        List<String> unrecorded = stations.stream()
                .filter(s -> !"true".equals(s.plans()) && !"false".equals(s.plans()))
                .map(Station::id).toList();
        assertTrue(unrecorded.isEmpty(),
                () -> "stations with no recorded verdict: " + unrecorded
                        + " — run the report and paste its column into gauntlet-stations.tsv");
    }

    /** The capture is worthless if it lost the course; a smoke check that it holds real terrain. */
    @Test
    void captureHoldsTheCourse() {
        assertTrue(world.recordedCells() > 10_000,
                () -> "capture only records " + world.recordedCells() + " cells");
    }
}
