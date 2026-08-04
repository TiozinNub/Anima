package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The second body through a wood pays for looking, not for measuring — but a shared shape is
 * still two separate beliefs, held by minds that can disagree later.
 *
 * <p>Each test subtracts a bare-ground control, so what is left is the price of measuring the
 * wood: what the cache should charge only once.
 */
class PoiSensorSharingTest {

    /** Inspects to 8, no skyline at all — this suite is about the near field's scans. */
    private static final AgentProfile EYED = eyed();

    private static final Pos HERE = new Pos(0, 64, 0);
    private static final double AHEAD = 0.0;

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 8.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 0.0,
                ProfileAspect.PLACES_CONE_DEGREES, 360.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 8.0,
                ProfileAspect.PLACES_REGION_MAX_SPREAD, 32.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_sharing_sensor");
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

    /** A stand whose canopies weld into one mass — the shape that costs real money to measure. */
    private static FakeProbe wood() {
        FakeProbe probe = new FakeProbe();
        for (int x = -4; x <= 4; x += 2) {
            for (int z = -4; z <= 4; z += 2) {
                probe.placeOak(x, z);
            }
        }
        return probe;
    }

    private static FakeProbe bareGround() {
        return new FakeProbe();
    }

    /** Walks one body on the spot until it has settled, and reports what the looking cost. */
    private static int walk(PoiSensorCore sensor, FakeProbe probe) {
        probe.reads = 0;
        for (int tick = 1; tick <= 300; tick++) {
            sensor.tick(HERE, AHEAD, tick, probe);
        }
        return probe.reads;
    }

    private static int walkAlone(FakeProbe probe, AgentKnowledge mind) {
        return walk(new PoiSensorCore(mind, EYED), probe);
    }

    @Test
    @DisplayName("with the shapes remembered, a wood costs about what bare ground costs")
    void theSecondBodyInheritsTheShapes() {
        int bare = walkAlone(bareGround(), new AgentKnowledge());
        int alone = walkAlone(wood(), new AgentKnowledge());
        int measuring = alone - bare;
        assertTrue(measuring > 0, "the fixture has to make measuring cost something");

        RegionCache shapes = new RegionCache();
        PlaceIndex places = new PlaceIndex();
        FakeProbe probe = wood();
        AgentKnowledge firstMind = new AgentKnowledge();
        int first = walk(new PoiSensorCore(firstMind, EYED, shapes, places), probe);
        AgentKnowledge secondMind = new AgentKnowledge();
        int second = walk(new PoiSensorCore(secondMind, EYED, shapes, places), probe);

        assertEquals(alone, first, "the first body through pays the full price, as it must");
        assertTrue(places.hits() > 0, "and leaves something behind for the next one");
        int stillPaid = second - bare;
        assertTrue(stillPaid * 5 < measuring,
                "the second body should pay a fraction of the measuring — paid " + stillPaid
                        + " of " + measuring);
        assertTrue(firstMind.size() > 0 && secondMind.size() == firstMind.size(),
                "and end up knowing exactly what the first one knows");
    }

    @Test
    @DisplayName("a shared shape is still two separate beliefs")
    void nobodyBecomesTelepathic() {
        RegionCache shapes = new RegionCache();
        PlaceIndex places = new PlaceIndex();
        FakeProbe probe = wood();

        AgentKnowledge firstMind = new AgentKnowledge();
        walk(new PoiSensorCore(firstMind, EYED, shapes, places), probe);
        AgentKnowledge secondMind = new AgentKnowledge();
        walk(new PoiSensorCore(secondMind, EYED, shapes, places), probe);

        int known = firstMind.size();
        assertTrue(known > 0, "both of them know the wood");
        PoiMemory theirs = secondMind.all(FakeGrowthRule.THICKET).iterator().next();
        assertTrue(secondMind.forget(FakeGrowthRule.THICKET, theirs.anchor()));

        assertEquals(known - 1, secondMind.size(), "one of them forgot a tree");
        assertEquals(known, firstMind.size(), "and the other still knows it");
    }

    @Test
    @DisplayName("without a shared pool, each body pays its own way — the cache is what changed")
    void theSavingIsTheCacheAndNotTheWorld() {
        FakeProbe probe = wood();
        int first = walkAlone(probe, new AgentKnowledge());
        int second = walkAlone(probe, new AgentKnowledge());

        assertEquals(first, second,
                "two strangers with nothing between them do identical work");
    }

    @Test
    @DisplayName("a felled tree is re-measured, not remembered wrong")
    void theWorldMovingCostsTheSavingBack() {
        RegionCache shapes = new RegionCache();
        PlaceIndex places = new PlaceIndex();
        FakeProbe probe = wood();
        walk(new PoiSensorCore(new AgentKnowledge(), EYED, shapes, places), probe);
        assertTrue(shapes.size() > 0);
        assertTrue(places.size() > 0);

        // One block goes, and with it every remembered shape that block could have belonged to,
        // and every recognised thing whose boundary that block had a say in.
        probe.clear(0, 65, 0);
        shapes.invalidate(0, 0);
        places.invalidate(0, 0);

        AgentKnowledge after = new AgentKnowledge();
        int cost = walk(new PoiSensorCore(after, EYED, shapes, places), probe);
        int alone = walkAlone(wood(), new AgentKnowledge());
        assertTrue(cost > alone / 2,
                "with the shape forgotten, the next body measures it again — cost " + cost);
        assertTrue(after.size() > 0, "and still ends up knowing the wood");
    }
}
