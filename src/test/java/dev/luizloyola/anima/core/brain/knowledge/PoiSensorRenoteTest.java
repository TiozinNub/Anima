package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tree the body still believes in is not news when it is seen again — even after the bounded
 * claim index has let go of its cells (run/forest, 2026-10-02: ~150 "noticed" lines per tree).
 */
class PoiSensorRenoteTest {

    /** Enough little oaks, 21 cells each, to overflow {@link ClaimIndex#MAX_CLAIMS}. */
    private static final int TREES = 220;
    /** Trunk spacing: canopies apart, and anchors outside the thicket's merge radius. */
    private static final int SPACING = 8;

    private static final AgentProfile EYED = eyed();

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 8.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 0.0,
                ProfileAspect.PLACES_CONE_DEGREES, 360.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 8.0,
                ProfileAspect.PLACES_MAX_PER_KIND, 1024.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_renote_sensor");
        for (ProfileAspect aspect : ProfileAspect.all()) {
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

    private long now = 1;

    /** Walks the body one block a tick from {@code fromX} to {@code toX} along z = 0. */
    private List<SenseEvent> walk(PoiSensorCore sensor, FakeProbe probe, int fromX, int toX) {
        List<SenseEvent> events = new ArrayList<>();
        int step = toX >= fromX ? 1 : -1;
        for (int x = fromX; x != toX + step; x += step) {
            events.addAll(sensor.tick(new Pos(x, 64, 0), step > 0 ? 270.0 : 90.0, now++, probe));
        }
        for (int i = 0; i < 40; i++) { // let the last growth finish
            events.addAll(sensor.tick(new Pos(toX, 64, 0), 0.0, now++, probe));
        }
        return events;
    }

    private static Map<Pos, Integer> notedPerAnchor(List<SenseEvent> events) {
        Map<Pos, Integer> noted = new HashMap<>();
        for (SenseEvent event : events) {
            if (event.type() == SenseEvent.Type.NOTED) {
                noted.merge(event.anchor(), 1, Integer::sum);
            }
        }
        return noted;
    }

    @Test
    @DisplayName("walking back past trees still believed in notes none of them again")
    void aKnownTreeIsRefreshedSilently() {
        FakeProbe probe = new FakeProbe();
        for (int i = 0; i < TREES; i++) {
            probe.placeOak(i * SPACING, 0);
        }
        AgentKnowledge mind = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(mind, EYED);
        int end = (TREES - 1) * SPACING;

        List<SenseEvent> out = walk(sensor, probe, 0, end);
        assertEquals(TREES, mind.size(), "the walk out learns every tree");
        assertEquals(ClaimIndex.MAX_CLAIMS, sensor.claimCount(),
                "the premise: the first trees' claims have fallen out of the index");

        List<SenseEvent> back = walk(sensor, probe, end, 0);
        assertEquals(TREES, mind.size(), "nothing was forgotten, nothing duplicated");
        assertFalse(back.stream().anyMatch(e -> e.type() == SenseEvent.Type.FORGOT));
        assertTrue(back.stream().noneMatch(e -> e.type() == SenseEvent.Type.NOTED),
                "re-noted on the way back: " + notedPerAnchor(back).size() + " trees");
        assertEquals(TREES, notedPerAnchor(out).size());
    }

    @Test
    @DisplayName("a known tree that changed is still news")
    void aChangedTreeIsNoted() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(8, 0);
        AgentKnowledge mind = new AgentKnowledge();
        PoiSensorCore sensor = new PoiSensorCore(mind, EYED);
        walk(sensor, probe, 0, 0);
        assertEquals(1, mind.size());

        // Outgrows its own claims: the surface above them is a fresh hypothesis, re-measured.
        probe.set(8, 68, 0, BlockKind.LOG);
        probe.set(8, 69, 0, BlockKind.LEAVES);
        walk(sensor, probe, 0, 200);
        List<SenseEvent> back = walk(sensor, probe, 200, 0);

        assertEquals(1, mind.size(), "merged, never duplicated");
        assertEquals(1, back.stream().filter(e -> e.type() == SenseEvent.Type.NOTED).count(),
                "the grown tree is journalled once");
    }

    @Test
    @DisplayName("a count is a change; two partial counts of one mass are not")
    void whatCountsAsChanged() {
        Pos at = new Pos(0, 64, 0);
        Region box = Region.of(at);
        PoiMemory five = new PoiMemory(FakeGrowthRule.THICKET, at, box, 5, false, 1);
        assertFalse(PoiSensorCore.changed(five, five.seenAt(9)));
        assertTrue(PoiSensorCore.changed(five,
                new PoiMemory(FakeGrowthRule.THICKET, at, box, 4, false, 9)));
        PoiMemory atLeast = new PoiMemory(FakeGrowthRule.THICKET, at, box, 1003, true, 1);
        assertFalse(PoiSensorCore.changed(atLeast,
                new PoiMemory(FakeGrowthRule.THICKET, at, box, 962, true, 9)));
        assertTrue(PoiSensorCore.changed(atLeast,
                new PoiMemory(FakeGrowthRule.THICKET, at, box, 1003, false, 9)));
    }
}
