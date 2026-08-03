package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The far sense: a forest on the skyline is made out, what is behind a hill or behind THEM is
 * not, and a forest arrives as a handful of glimpses.
 *
 * <p>Anima ships no botany, so the suite registers a growth rule of its own.
 */
class HorizonScannerTest {

    /** A 12-block reach with a 40-block skyline, and a human 150° aperture. */
    private static final AgentProfile EYED = eyed(12, 40);

    private static final Pos HERE = new Pos(0, 64, 0);
    /** Minecraft's convention: yaw 0 faces +Z, so "ahead" is increasing z. */
    private static final double AHEAD = 0.0;

    private static AgentProfile eyed(int radius, int horizon) {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, (double) radius,
                ProfileAspect.PLACES_HORIZON_RADIUS, (double) horizon,
                ProfileAspect.PLACES_CONE_DEGREES, 150.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 4.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_eyed");
        for (ProfileAspect aspect : ProfileAspect.values()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }

    /**
     * A second kind of thing, so two can stand on the same ground: a dedup by where alone reads
     * as correct until the first kind seen silences every other one forever.
     */
    private static final class FakePondRule implements GrowthRule {
        static final PoiKind POND = PoiKind.register("test_pond", 4, "");
        static final FakePondRule INSTANCE = new FakePondRule();

        @Override
        public PoiKind kind() {
            return POND;
        }

        @Override
        public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
            return kind == BlockKind.WATER;
        }

        @Override
        public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe) {
            return blocks.isEmpty() ? List.of()
                    : List.of(new Evaluation(seed, blocks.size(), blocks));
        }
    }

    @BeforeEach
    void registerWhatGrows() {
        FakeGrowthRule.register();
        GrowthRules.register(BlockKind.WATER, FakePondRule.INSTANCE);
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    private final HorizonScanner scanner = new HorizonScanner(EYED);
    /** Not 0 on the first tick — but see {@link #aFreshWorldStartsAtTimeZero}. */
    private long now = 1;

    /** Sweeps for a while on the leftover-sized budget the sensor would really hand it. */
    private List<SenseEvent> sweep(FakeProbe probe, int ticks) {
        return sweep(probe, ticks, AHEAD);
    }

    private List<SenseEvent> sweep(FakeProbe probe, int ticks, double yaw) {
        List<SenseEvent> events = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            scanner.step(HERE, yaw, now++, probe, 64, events);
        }
        return events;
    }

    private static List<SenseEvent> glimpses(List<SenseEvent> events) {
        return events.stream().filter(e -> e.type() == SenseEvent.Type.GLIMPSED).toList();
    }

    @Test
    void aTreeFarBeyondInspectionRangeIsMadeOut() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30); // 30 blocks out: well past the 12-block near field

        List<SenseEvent> seen = glimpses(sweep(probe, 60));

        assertEquals(1, seen.size(), "one thicket made out, got " + seen);
        assertEquals(FakeGrowthRule.THICKET, seen.get(0).kind());
        assertTrue(seen.get(0).anchor().z() > 12, "and it is out past what could be inspected");
    }

    @Test
    void aTreeStandingInTheOpenIsFoundByItsCROWN() {
        // Broke live: a bearing passing BESIDE the one-block trunk flies clear under the crown,
        // so stopping on the first clear ray never looked at the canopy one ray higher.
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 34);

        assertFalse(glimpses(sweep(probe, 90)).isEmpty(),
                "an oak in plain view at 34 blocks is exactly what this sense is for");
    }

    @Test
    void aTreeThatGrowsOnGroundTheyHaveAlreadyLookedAtIsStillNoticed() {
        // Having seen this ground before must not make a tree grown on it invisible — which is
        // what remembering "there was nothing in that cell" would do.
        FakeProbe probe = new FakeProbe();
        assertTrue(glimpses(sweep(probe, 140)).isEmpty(), "empty ground is empty");

        probe.placeOak(0, 30);
        now += HorizonScanner.REFRESH_TICKS + 1;

        assertFalse(glimpses(sweep(probe, 140)).isEmpty(),
                "and a tree grown in plain view is a tree they can see");
    }

    @Test
    void nothingBehindThemIsEverMadeOut() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, -30); // squarely at their back

        assertTrue(glimpses(sweep(probe, 60)).isEmpty(),
                "the passive sense never looks behind — that is what the survey is for");
    }

    @Test
    void turningRoundFindsIt() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, -30);

        assertTrue(glimpses(sweep(probe, 40)).isEmpty(), "not while facing away");
        assertFalse(glimpses(sweep(probe, 60, 180.0)).isEmpty(), "but they can turn and look");
    }

    @Test
    void aRidgeHidesTheForestBehindIt() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        // A wall across the view at 20 blocks, taller than the canopy is from here.
        for (int x = -12; x <= 12; x++) {
            for (int y = 64; y <= 78; y++) {
                probe.set(x, y, 20, BlockKind.OTHER);
            }
        }

        assertTrue(glimpses(sweep(probe, 60)).isEmpty(),
                "every ray that way stops on the wall");
    }

    @Test
    void aGapInTheRidgeIsEnoughToSeeThrough() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        // The same wall, two courses left out on the eye-to-tree line. The old skyline walk
        // occluded everything behind it by arithmetic; a ray goes through the hole.
        for (int x = -12; x <= 12; x++) {
            for (int y = 64; y <= 78; y++) {
                if (y == 66 || y == 67) {
                    continue;
                }
                probe.set(x, y, 20, BlockKind.OTHER);
            }
        }

        assertFalse(glimpses(sweep(probe, 80)).isEmpty(),
                "a body can see through a gap it is level with");
    }

    @Test
    void aWallAtArmsLengthHidesEverything() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        // Four blocks off: the old walk never looked inside inspection range, so a barn in the
        // way cost a wasted confirm-ray.
        for (int x = -12; x <= 12; x++) {
            for (int y = 64; y <= 78; y++) {
                probe.set(x, y, 4, BlockKind.OTHER);
            }
        }

        assertTrue(glimpses(sweep(probe, 60)).isEmpty(),
                "rays start at the eye, so what is near enough to block really does block");
    }

    @Test
    void aBearingStopsHonestlyWhereTheWorldStops() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        for (int x = -40; x <= 40; x++) {
            for (int z = 18; z <= 22; z++) {
                probe.markUnloaded(x, z);
            }
        }

        assertTrue(glimpses(sweep(probe, 60)).isEmpty(),
                "unloaded is 'I could see no further', not 'there was nothing there'");
    }

    @Test
    void aForestArrivesAsAHandfulOfGlimpsesNotOnePerTrunk() {
        FakeProbe probe = new FakeProbe();
        int trunks = 0;
        for (int x = -8; x <= 8; x += 4) {
            for (int z = 24; z <= 32; z += 4) {
                probe.placeOak(x, z);
                trunks++;
            }
        }

        List<SenseEvent> seen = glimpses(sweep(probe, 80));

        assertTrue(seen.size() >= 1, "the wood is noticed");
        assertTrue(seen.size() < trunks,
                trunks + " trunks must not be " + seen.size() + " glimpses — a forest is a gist");
    }

    @Test
    void theSweepGoesQuietOnceTheSkylineIsFreshAndWakesWhenItStales() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        // Long enough to finish every bearing in the cone: a fan of marching rays costs several
        // times the old heightmap walk, so a bearing then rests for REFRESH_TICKS.
        sweep(probe, 140);

        int before = probe.reads;
        scanner.step(HERE, AHEAD, now, probe, 64, new ArrayList<>());
        assertEquals(before, probe.reads, "a fresh skyline costs nothing at all");

        now += HorizonScanner.REFRESH_TICKS + 1;
        scanner.step(HERE, AHEAD, now++, probe, 64, new ArrayList<>());
        assertTrue(probe.reads > before, "but it is looked at again eventually");
    }

    @Test
    void theSweepNeverOutspendsItsWalletByMoreThanOneSample() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        // One step may overrun by the read that classifies what the ray landed on.
        int slack = 2;
        for (int i = 0; i < 60; i++) {
            int before = probe.reads;
            int claimed = scanner.step(HERE, AHEAD, now++, probe, 24, new ArrayList<>());
            assertTrue(claimed <= 24 + slack, "claimed " + claimed + " of a 24 budget");
            assertTrue(probe.reads - before <= 24 + slack,
                    "actually spent " + (probe.reads - before) + " of a 24 budget");
        }
    }

    @Test
    void fellingWhatToppedABearingDropsItBackToTheGround() {
        FakeProbe probe = new FakeProbe();
        // A pillar on an otherwise empty bearing, tall enough that it is what the fan lands on.
        for (int y = 64; y <= 78; y++) {
            probe.set(0, y, 30, BlockKind.OTHER);
        }
        sweep(probe, 90);
        int bin = HorizonBuffer.binOf(0.0);
        assertTrue(scanner.buffer().tan(bin) > 0, "it stands above their eye, so the bearing does");

        for (int y = 64; y <= 78; y++) {
            probe.clear(0, y, 30);
        }
        now += HorizonScanner.REFRESH_TICKS + 1;
        sweep(probe, 90);

        // Not empty: the open ground out there is the horizon now — only the pillar must go.
        assertTrue(scanner.buffer().tan(bin) < 0,
                "once it is felled nothing that way rises to their eye any more");
        assertTrue(scanner.buffer().top(bin).y() <= FakeProbe.GROUND_Y,
                "and what tops the bearing is the ground, not a memory of the pillar");
    }

    @Test
    void aFreshWorldStartsAtTimeZero() {
        // Game time 0 is a real value, not a "never swept" sentinel — a body spawned into a new
        // world must still be able to finish a bearing and let it rest.
        FakeProbe probe = new FakeProbe();
        HorizonScanner fresh = new HorizonScanner(EYED);
        for (int tick = 0; tick < 140; tick++) {
            fresh.step(HERE, AHEAD, tick, probe, 64, new ArrayList<>());
        }

        int before = probe.reads;
        fresh.step(HERE, AHEAD, 0, probe, 64, new ArrayList<>());
        assertEquals(before, probe.reads, "swept at time 0 must count as swept");
    }

    /**
     * A one-block sheet of canopy at {@code z}: tall enough to cover every airborne ray, narrow
     * enough that only bearings reaching a trunk at (0, 30) cross it.
     */
    private static void leafScreen(FakeProbe probe, int z) {
        for (int x = -3; x <= 3; x++) {
            for (int y = 64; y <= 70; y++) {
                probe.set(x, y, z, BlockKind.LEAVES);
            }
        }
    }

    @Test
    void aCanopyNearEnoughIsLookedBETWEEN() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        leafScreen(probe, 6); // inside the 8-block see-into reach

        List<SenseEvent> seen = glimpses(sweep(probe, 90));

        assertTrue(seen.stream().anyMatch(e -> e.anchor().z() >= 28),
                "branches at arm's length have gaps in them, and this is looking through one of "
                        + "them at the wood beyond: " + seen);
    }

    @Test
    void theSameCanopyAtRangeIsAWall() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        leafScreen(probe, 20); // past the reach, so the eye no longer resolves its parts

        List<SenseEvent> seen = glimpses(sweep(probe, 90));

        assertFalse(seen.isEmpty(),
                "the screen itself is still made out — stopping a ray is not being invisible to "
                        + "it, or a wood could only ever be found by threading its trunks");
        assertTrue(seen.stream().allMatch(e -> e.anchor().z() <= 20),
                "and nothing behind it is: a body does not see through a wood: " + seen);
    }

    @Test
    void aCellThatAnsweredForOneKindStillAnswersForAnother() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);

        List<SenseEvent> wood = glimpses(sweep(probe, 90));
        assertEquals(1, wood.size(), "the thicket is made out: " + wood);
        assertEquals(FakeGrowthRule.THICKET, wood.get(0).kind());

        // Something else in the very cells the thicket stood in: the forest dedup must not
        // silence a cell for another kind.
        probe.removeOak(0, 30);
        for (int y = 64; y <= 66; y++) {
            probe.set(0, y, 30, BlockKind.WATER);
        }
        now += HorizonScanner.REFRESH_TICKS + 1;

        List<SenseEvent> pond = glimpses(sweep(probe, 90));

        assertFalse(pond.isEmpty(), "a cell answered for trees is not answered for water");
        assertTrue(pond.stream().allMatch(e -> e.kind() == FakePondRule.POND),
                "and what it answers with is the thing that is actually there: " + pond);
    }

    @Test
    void aBodyWithNoSkylineSpendsNothing() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        HorizonScanner blind = new HorizonScanner(TestSpecies.PROFILE); // horizon_radius 0

        List<SenseEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            assertEquals(0, blind.step(HERE, AHEAD, now++, probe, 64, events));
        }
        assertEquals(0, probe.reads);
        assertTrue(events.isEmpty());
    }
}
