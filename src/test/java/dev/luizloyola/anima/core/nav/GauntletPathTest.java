package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
     * Stations whose whole subject is the water. Same disease as {@link #DIAGONAL_ONLY}: H6 passed
     * for a route that walked the length of its pool's retaining wall and dropped onto the goal pad
     * without getting wet.
     *
     * <p>The assertion is that a leg ends standing IN a water cell, not that the route contains a
     * {@link MoveType#SWIM} — the move is what the planner calls it, the cell is what the world
     * says. A surface swimmer's feet cell is the water cell, so this is a direct reading.
     *
     * <p>E3 is absent: a two-wide channel is meant to be LEAPT (see
     * {@code PathfinderSwimTest.stillLeapsNarrowWaterRatherThanSwim}). E5–E8 are absent because
     * nothing plans them yet; add each one here as its rung lands.
     */
    private static final List<String> MUST_GET_WET = List.of("E1", "E2", "E4", "H6");

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
