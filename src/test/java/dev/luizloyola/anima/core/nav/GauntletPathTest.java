package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
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
 *
 * <p><b>Two layers.</b> Every station is asked again by a body carrying blocks it may lay
 * ({@link #BLOCKS}), locked in its own column, {@code plansBlocks}. That search is fenced to its
 * station's lane ({@link #lane}): the course keeps lanes apart with the void, and a body that may
 * build crosses the void — round a lava pool through the air beside it, or into the next lane — which
 * answers no station's question.
 */
class GauntletPathTest {

    /**
     * The body every expectation was recorded for — the Person's declared capabilities, on a walk
     * allowed to scale a soft step, which a hand does with nothing in the pocket.
     */
    private static final MoveCapabilities BODY = TestBodies.BIPED.withScaling(true);

    private record Station(String id, int sx, int sy, int sz, int gx, int gy, int gz,
                           String plans, String title, String plansBlocks, String spentBlocks) {
    }

    /** The second layer's body: the first one, with a stack of blocks it may lay. */
    private static final MoveCapabilities BLOCKS = BODY.withLaid(64);

    /** One fence per station, built once — see the class doc. */
    private static final Map<String, NavDomain> LANES = new java.util.HashMap<>();

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
                    f[7], f.length > 9 ? f[9] : "", f.length > 11 ? f[11] : "?",
                    f.length > 12 ? f[12] : "?"));
        }
        return out;
    }

    static Stream<Station> stations() {
        return stations.stream();
    }

    /**
     * A station's request, carrying the capture's recorded pillars as a walk in the world carries the
     * record's: K13 and K14 are about them, and they are not terrain.
     */
    private static PathRequest ask(Station s, MoveCapabilities body) {
        return PathRequest.of(s.sx(), s.sy(), s.sz(), s.gx(), s.gy(), s.gz(), body).near(world.pillars());
    }

    private static boolean plans(Station s) {
        return Pathfinder.find(world,
                ask(s, BODY)).reachedGoal();
    }

    /**
     * The station's own lane, every height: its start and goal columns widened by the two cells either
     * side a lane runs to. Every lane and gallery stub runs along one axis, so this is the lane.
     */
    private static NavDomain lane(Station s) {
        return LANES.computeIfAbsent(s.id(), id -> {
            List<Pos> cells = new ArrayList<>();
            for (int x = Math.min(s.sx(), s.gx()) - 2; x <= Math.max(s.sx(), s.gx()) + 2; x++) {
                for (int z = Math.min(s.sz(), s.gz()) - 2; z <= Math.max(s.sz(), s.gz()) + 2; z++) {
                    for (int y = -64; y <= -30; y++) {
                        cells.add(new Pos(x, y, z));
                    }
                }
            }
            return NavDomain.of(cells);
        });
    }

    private static dev.luizloyola.anima.core.nav.Path withBlocks(Station s) {
        return Pathfinder.find(world,
                ask(s, BLOCKS).within(lane(s)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void plannerVerdictWithBlocksIsUnchanged(Station s) {
        assertEquals(Boolean.parseBoolean(s.plansBlocks()), withBlocks(s).reachedGoal(),
                () -> s.id() + " (" + s.title() + "): with blocks in hand the planner changed its "
                        + "mind about whether it can reach " + s.gx() + " " + s.gy() + " " + s.gz()
                        + ". If that was the point of your change, re-record the row.");
    }

    /**
     * What the route with blocks costs the pocket, locked like the verdicts: a change in how blocks
     * are laid, taken or carved moves this number, and the message says by how much.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void plannerBlockUseIsUnchanged(Station s) {
        int spent = withBlocks(s).spent();
        int recorded = Integer.parseInt(s.spentBlocks());
        assertEquals(recorded, spent,
                () -> s.id() + " (" + s.title() + "): the route with blocks now spends " + spent
                        + " from the pocket, " + (spent > recorded ? "+" : "") + (spent - recorded)
                        + " on the recorded " + recorded + ". If that was the point of your change, "
                        + "re-record the row.");
    }

    /** The first layer's walkability rule, for routes that lay: each lay is a cell there is room for. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stations")
    void everyBuiltRouteIsWalkableInTheWorldItWasPlannedIn(Station s) {
        List<Waypoint> route = withBlocks(s).waypoints();
        Waypoint previous = new Waypoint(s.sx(), s.sy(), s.sz(), MoveType.WALK);
        for (Waypoint to : route) {
            Waypoint from = previous;
            for (CellNeed need : PathIntegrity.edgeNeeds(from, to, BLOCKS)) {
                assertTrue(NavGrids.satisfies(world, need),
                        () -> s.id() + " (" + s.title() + "): the built route asks for " + need.need()
                                + " at " + need.x() + " " + need.y() + " " + need.z()
                                + " on the edge into " + to.move() + " " + to.x() + " " + to.y()
                                + " " + to.z() + ". Full route: " + route);
            }
            previous = to;
        }
    }

    /**
     * K1's rim is a short way round, and a body with blocks must still take it: reaching the pad by
     * bridging is a false pass there.
     */
    @Test
    void theRimRoundTheRavineIsWalkedNotBridged() {
        Station s = stations.stream().filter(st -> st.id().equals("K1")).findFirst().orElseThrow();
        dev.luizloyola.anima.core.nav.Path path = withBlocks(s);
        assertTrue(path.reachedGoal(), "K1 is reached");
        assertEquals(0, path.laid(), () -> "K1 is reached by laying: " + path.waypoints());
    }

    /**
     * The recorded pillars, by a hand with nothing in it: K13 climbs out of the pit on blocks taken
     * off one, K14 goes down one breaking it, and K15 — K13 with its pillar unrecorded — is the
     * control, refused by the lock.
     */
    @Test
    void aRecordedPillarIsClimbedBesideAndGoneDown() {
        assertTrue(routeOf("K13").stream().anyMatch(w -> w.move() == MoveType.PILLAR),
                () -> "K13 leaves the pit without a block off the pillar: " + routeOf("K13"));
        assertTrue(routeOf("K14").stream().anyMatch(w -> w.move() == MoveType.LOWER),
                () -> "K14 reaches the floor without going down the pillar: " + routeOf("K14"));
    }

    /**
     * Carving: K7's grass step is carved; K16, the same step in podzol, is scaled; K9's staircase
     * is carved at most at its top lip, since a carve under the next step leaves that step three high.
     */
    @Test
    void grassIsCarvedAndPodzolAndAStaircaseAreScaled() {
        assertTrue(routeOf("K7").stream().anyMatch(w -> w.move() == MoveType.CARVE),
                () -> "K7 is not carved: " + routeOf("K7"));
        List<Waypoint> podzol = routeOf("K16");
        assertTrue(podzol.stream().noneMatch(w -> w.move() == MoveType.CARVE)
                        && podzol.stream().anyMatch(w -> w.move() == MoveType.SCALE),
                () -> "K16 is not scaled: " + podzol);
        Station k9 = stations.stream().filter(st -> st.id().equals("K9")).findFirst().orElseThrow();
        List<Waypoint> slope = routeOf("K9");
        assertTrue(slope.stream().filter(w -> w.move() == MoveType.CARVE).allMatch(w -> w.x() == k9.sx() + 8),
                () -> "K9 is carved under a step: " + slope);
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
                    ask(s, BODY)
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
                ask(s, BODY)).waypoints();
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
                    ask(s, BODY)).waypoints();
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
            var result = Pathfinder.find(world,
                    ask(s, BODY));
            if (!result.reachedGoal()) {
                // A refusal is the reachability lock's business. Read as a shortcut it is nonsense:
                // a partial route stops early, so of course it misses rows. Caught the day A13
                // became unplannable.
                continue;
            }
            List<Waypoint> route = result.waypoints();
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
                    ask(s, BODY)).waypoints();
            assertTrue(route.stream().noneMatch(w -> w.move() == MoveType.LEAP),
                    id + " (" + s.title() + ") is solved by leaping, so it measures leaps rather "
                            + "than diagonals: " + route);
        }
    }

    /**
     * G11's subject is two square turns around berry bushes, which the follower once cut. A route
     * that leapt the bushes, or went round them some other way, would pass without ever turning
     * where the bushes are — so the route must stand on both corners, and walk.
     */
    @Test
    void theBushChicaneIsWalkedRoundItsCorners() {
        Station s = stations.stream().filter(st -> st.id().equals("G11")).findFirst()
                .orElseThrow(() -> new AssertionError("no station G11"));
        List<Waypoint> route = Pathfinder.find(world,
                ask(s, BODY)).waypoints();
        for (int[] corner : new int[][] {{753, 123}, {753, 124}}) {
            assertTrue(route.stream().anyMatch(w -> w.x() == corner[0] && w.z() == corner[1]),
                    () -> "G11 never turns at (" + corner[0] + ", " + corner[1] + "): " + route);
        }
        assertTrue(route.stream().noneMatch(w -> w.move() == MoveType.LEAP),
                "G11 is leapt rather than walked: " + route);
    }

    /**
     * G12 is reached across its wheat as readily as round it, so a planner that forgot farmland
     * still passes it: the route must never stand on the field. G13 has no free row, and refuses.
     */
    @Test
    void theWheatFieldIsWalkedRoundNotAcross() {
        List<Waypoint> route = routeOf("G12");
        assertTrue(route.stream().noneMatch(w -> world.cell(w.x(), w.y(), w.z()) == CellType.STEP
                        && world.farmland(w.x(), w.y(), w.z())),
                () -> "G12 crosses its wheat field: " + route);
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
                ask(s, BODY)).waypoints();
        assertTrue(route.stream().anyMatch(w -> w.move() == MoveType.DIVE),
                "E6 is reached without ever going under: " + route);
    }

    @Test
    void waterStationsAreNotQuietlySolvedOnDryLand() {
        for (String id : MUST_GET_WET) {
            Station s = stations.stream().filter(st -> st.id().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no station " + id));
            List<Waypoint> route = Pathfinder.find(world,
                    ask(s, BODY)).waypoints();
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
    /**
     * Stations whose whole subject is a climb: reached without a {@link MoveType#CLIMB} would mean
     * a jump or a leap found another way up, and the row would be about that instead.
     */
    private static final List<String> MUST_CLIMB =
            List.of("D1", "D2", "D3", "D4", "D9", "D12", "D13", "D14", "D17", "D18",
                    "I2.9");
    /** Stations whose whole subject is a door or a gate: the route must stand in its doorway. */
    private static final List<String> MUST_USE_THE_DOOR =
            List.of("F1", "F2", "F3", "F8", "F12", "F13", "F14", "F15", "F16", "F17", "F19", "I2.7");
    /**
     * Staircases: walked by their treads, so neither jumped up nor dropped down. They were both
     * until 2026-09-26, and reached the goal all the same, so reaching it proves nothing here.
     */
    private static final List<String> MUST_WALK_THE_STAIRS = List.of("B5", "B6", "B20", "B22");

    @Test
    void climbDoorAndStairStationsAreNotQuietlySolvedAnotherWay() {
        for (String id : MUST_CLIMB) {
            List<Waypoint> route = routeOf(id);
            assertTrue(route.stream().anyMatch(w -> world.cell(w.x(), w.y(), w.z()) == CellType.CLIMB),
                    id + " is reached without ever holding the climbable: " + route);
        }
        for (String id : MUST_USE_THE_DOOR) {
            List<Waypoint> route = routeOf(id);
            assertTrue(route.stream().anyMatch(w -> world.cell(w.x(), w.y(), w.z()) == CellType.DOOR),
                    id + " is reached without ever standing in the doorway: " + route);
        }
        for (String id : MUST_WALK_THE_STAIRS) {
            List<Waypoint> route = routeOf(id);
            assertTrue(route.stream().noneMatch(w -> w.move() == MoveType.JUMP || w.move() == MoveType.DROP),
                    id + " hops or drops down a staircase it should walk: " + route);
        }
    }

    /**
     * A8's corner diagonal: a 3-cell gap off its fourth block, a 2-cell gap off its fifth. The
     * fourth has one diagonal step of run-up and landed with 0.48 of a block to spare; the fifth is
     * the line a player takes and lands with 0.63 (Luiz, 2026-09-27).
     */
    @Test
    void theCornerDiagonalIsLeaptFromItsLastBlock() {
        List<Waypoint> route = routeOf("A8");
        int leap = -1;
        for (int i = 1; i < route.size(); i++) {
            if (route.get(i).move() == MoveType.LEAP) {
                leap = i;
            }
        }
        assertTrue(leap > 0, "A8 crosses without a leap: " + route);
        Waypoint takeoff = route.get(leap - 1);
        assertEquals(List.of(612, 90), List.of(takeoff.x(), takeoff.z()),
                "A8 leaps from short of its last block: " + route);
    }

    private static List<Waypoint> routeOf(String id) {
        Station s = stations.stream().filter(st -> st.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no station " + id));
        dev.luizloyola.anima.core.nav.Path path = Pathfinder.find(world,
                ask(s, BODY));
        assertTrue(path.reachedGoal(), id + " (" + s.title() + ") is not reached at all");
        return path.waypoints();
    }

    @Test
    void report() throws IOException {
        StringBuilder out = new StringBuilder(
                "# id\tplans\trecorded\ttitle\tplansBlocks\trecordedBlocks\tspentBlocks\trecordedSpent\n");
        int agree = 0;
        for (Station s : stations) {
            boolean actual = plans(s);
            if (String.valueOf(actual).equals(s.plans())) {
                agree++;
            }
            out.append(s.id()).append('\t').append(actual).append('\t')
                    .append(s.plans()).append('\t').append(s.title()).append('\t')
                    .append(withBlocks(s).reachedGoal()).append('\t').append(s.plansBlocks())
                    .append('\t').append(withBlocks(s).spent()).append('\t').append(s.spentBlocks())
                    .append('\n');
        }
        Path file = Path.of("build", "gauntlet-plans.tsv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        System.out.println("gauntlet: " + agree + "/" + stations.size()
                + " stations match their recorded verdict -> " + file.toAbsolutePath());
    }

    private static boolean recorded(String verdict) {
        return "true".equals(verdict) || "false".equals(verdict);
    }

    /** A row nobody recorded is a row nobody looked at; an all-{@code ?} table would pass. */
    @Test
    void everyStationIsRecorded() {
        List<String> unrecorded = stations.stream()
                .filter(s -> !recorded(s.plans()) || !recorded(s.plansBlocks())
                        || !s.spentBlocks().matches("-?\\d+"))
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
