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

    @BeforeEach
    void registerWhatGrows() {
        FakeGrowthRule.register();
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
                "the running skyline occludes it — and no ray was spent finding that out");
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
        sweep(probe, 60);

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
        // One sample may overrun by its confirm-ray: surfaceY + at + HORIZON_RAY_COST.
        int slack = 2 + HorizonScanner.HORIZON_RAY_COST;
        for (int i = 0; i < 60; i++) {
            int before = probe.reads;
            int claimed = scanner.step(HERE, AHEAD, now++, probe, 24, new ArrayList<>());
            assertTrue(claimed <= 24 + slack, "claimed " + claimed + " of a 24 budget");
            assertTrue(probe.reads - before <= 24 + slack,
                    "actually spent " + (probe.reads - before) + " of a 24 budget");
        }
    }

    @Test
    void aFreshWorldStartsAtTimeZero() {
        // Game time 0 is a real value, not a "never swept" sentinel — a body spawned into a new
        // world must still be able to finish a bearing and let it rest.
        FakeProbe probe = new FakeProbe();
        HorizonScanner fresh = new HorizonScanner(EYED);
        for (int tick = 0; tick < 60; tick++) {
            fresh.step(HERE, AHEAD, tick, probe, 64, new ArrayList<>());
        }

        int before = probe.reads;
        fresh.step(HERE, AHEAD, 0, probe, 64, new ArrayList<>());
        assertEquals(before, probe.reads, "swept at time 0 must count as swept");
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
