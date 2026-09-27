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
import org.junit.jupiter.api.Test;

/** A tree seen only through leaves is retried a while, and forgotten once it is left behind. */
class PoiSensorRetryTest {

    private static final AgentProfile EYED = eyed();
    private static final Pos HERE = new Pos(0, 64, 0);
    private static final Pos FAR = new Pos(1000, 64, 0);
    /** Past the last retry: RAY_RETRY_MAX attempts, RAY_RETRY_DELAY_TICKS apart. */
    private static final int SPENT = PoiSensorCore.RAY_RETRY_MAX
            * PoiSensorCore.RAY_RETRY_DELAY_TICKS + 60;

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 8.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 0.0,
                ProfileAspect.PLACES_CONE_DEGREES, 360.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 8.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_retry_sensor");
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

    /** An oak at (3, 0) whose every canopy top is out of sight. */
    private static FakeProbe hiddenOak() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(3, 0);
        for (int x = 2; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                probe.hide(new Pos(x, 68, z));
            }
        }
        return probe;
    }

    @Test
    void aSpentRetryIsDroppedOnceOutOfRange() {
        FakeProbe probe = hiddenOak();
        PoiSensorCore sensor = new PoiSensorCore(new AgentKnowledge(), EYED);
        for (int tick = 1; tick <= SPENT; tick++) {
            sensor.tick(HERE, 0.0, tick, probe);
        }
        assertTrue(sensor.retriesHeld() > 0, "spent retries stay parked while still in range");

        sensor.tick(FAR, 0.0, SPENT + 1, probe);
        assertEquals(0, sensor.retriesHeld(), "and go once the body has left them behind");
    }

    @Test
    void aRetryStillOwedSurvivesLeaving() {
        FakeProbe probe = hiddenOak();
        PoiSensorCore sensor = new PoiSensorCore(new AgentKnowledge(), EYED);
        for (int tick = 1; tick <= 5; tick++) {
            sensor.tick(HERE, 0.0, tick, probe);
        }
        int owed = sensor.retriesHeld();
        assertTrue(owed > 0, "the fixture has to overlook something");

        sensor.tick(FAR, 0.0, 6, probe);
        assertEquals(owed, sensor.retriesHeld(), "a scheduled look is kept, as it always was");
    }
}
