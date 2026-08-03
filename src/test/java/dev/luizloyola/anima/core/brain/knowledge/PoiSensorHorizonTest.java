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
 * The two tiers assembled: the near field inspects what it can reach, the skyline sweep takes
 * what it did not want. The scheduling <em>is</em> the priority order inside one wallet — a body
 * head-down in new ground scans no skyline.
 */
class PoiSensorHorizonTest {

    /** Inspects to 12, makes out a skyline to 40. */
    private static final AgentProfile EYED = eyed();

    private static final Pos HERE = new Pos(0, 64, 0);
    private static final double AHEAD = 0.0;

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 12.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 40.0,
                ProfileAspect.PLACES_CONE_DEGREES, 150.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 4.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_eyed_sensor");
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

    private final AgentKnowledge knowledge = new AgentKnowledge();
    private final PoiSensorCore sensor = new PoiSensorCore(knowledge, EYED);
    private long now = 1;

    private List<SenseEvent> tick(FakeProbe probe, int ticks) {
        return tickAt(probe, HERE, ticks);
    }

    private List<SenseEvent> tickAt(FakeProbe probe, Pos feet, int ticks) {
        List<SenseEvent> events = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            events.addAll(sensor.tick(feet, AHEAD, now++, probe));
        }
        return events;
    }

    private static List<SenseEvent> ofType(List<SenseEvent> events, SenseEvent.Type type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    @Test
    void theNearTreeIsInspectedAndTheFarOneIsOnlyMadeOut() {
        FakeProbe probe = new FakeProbe();
        // Ahead and to one side: inside the near field's cone, and off the bearing the far tree
        // sits on, so neither hides the other.
        probe.placeOak(-5, 4);  // inspected, counted, remembered
        probe.placeOak(0, 30);  // only on the skyline: a gist

        List<SenseEvent> events = tick(probe, 120);

        List<SenseEvent> noted = ofType(events, SenseEvent.Type.NOTED);
        List<SenseEvent> glimpsed = ofType(events, SenseEvent.Type.GLIMPSED);
        assertEquals(1, noted.size(), "the near tree is a belief, got " + noted);
        assertTrue(noted.get(0).memory().units() > 0, "with something counted in it");
        assertEquals(1, glimpsed.size(), "the far tree is only a glimpse, got " + glimpsed);
        assertTrue(glimpsed.get(0).anchor().z() > 12, "and it is out past inspection range");
        assertFalse(knowledge.all(FakeGrowthRule.THICKET).stream()
                        .anyMatch(m -> m.anchor().z() > 12),
                "a glimpse is NOT a belief — nothing far entered the store");
    }

    @Test
    void theSkylineWaitsWhileTheNearFieldIsBusy() {
        FakeProbe probe = new FakeProbe();
        // A wall of trunks inside the near field: the queue and the growths eat the whole wallet.
        for (int x = -10; x <= 10; x += 2) {
            for (int z = -10; z <= 10; z += 2) {
                probe.placeOak(x, z);
            }
        }
        probe.placeOak(0, 30);

        // The first glance queues a whole view. While that is draining, nothing far is looked at.
        List<SenseEvent> early = tick(probe, 3);

        assertTrue(ofType(early, SenseEvent.Type.GLIMPSED).isEmpty(),
                "head-down in new ground, they scan no skyline");
    }

    @Test
    void walkingUpToAGlimpseTurnsItIntoABelief() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);

        tick(probe, 60);
        assertEquals(1, knowledge.glimpseCount(), "made out from afar");
        assertEquals(0, knowledge.size(), "but not yet known");

        // Close enough that the near field reaches it.
        tickAt(probe, new Pos(0, 64, 22), 120);

        assertEquals(0, knowledge.glimpseCount(), "the rumour is spent");
        assertEquals(1, knowledge.size(), "and they know the tree instead");
        assertTrue(knowledge.all(FakeGrowthRule.THICKET).iterator().next().units() > 0,
                "with something actually counted in it");
    }

    @Test
    void walkingUpToNothingDisprovesTheGlimpse() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        tick(probe, 60);
        assertEquals(1, knowledge.glimpseCount());

        // It was there when they looked, and is gone by the time they arrive.
        probe.removeOak(0, 30);
        tickAt(probe, new Pos(0, 64, 22), 120);

        assertEquals(0, knowledge.glimpseCount(),
                "they went to look and there was nothing — the gist does not outlive that");
        assertEquals(0, knowledge.size(), "and nothing was invented to replace it");
    }

    @Test
    void theWholeSensorStillHonoursOneWallet() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, 30);
        probe.placeOak(-5, 4);
        // Near field slack for a growth's neighbour burst plus the far sense's confirm-ray.
        int ceiling = PoiSensorCore.readsPerTick() + 40 + HorizonScanner.HORIZON_RAY_COST;

        for (int i = 0; i < 120; i++) {
            int before = probe.reads;
            sensor.tick(HERE, AHEAD, now++, probe);
            assertTrue(probe.reads - before <= ceiling,
                    "tick spent " + (probe.reads - before) + " reads");
        }
    }
}
