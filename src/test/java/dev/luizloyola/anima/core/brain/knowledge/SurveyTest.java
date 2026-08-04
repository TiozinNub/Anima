package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deliberate look: what a body finds by stopping and turning round that walking past never
 * could. Two failures — where the fan points, and what a ray is willing to notice.
 */
class SurveyTest {

    private static final Pos HERE = new Pos(0, 64, 0);
    private static final double AHEAD = 0.0;

    /** A stalk of something: present, and far too slight to stop a ray. */
    private static final BlockKind CANE = BlockKind.register("test_cane");

    private static final class CaneRule implements GrowthRule {
        static final PoiKind BRAKE = PoiKind.register("test_brake", 4, "");
        static final CaneRule INSTANCE = new CaneRule();

        @Override
        public PoiKind kind() {
            return BRAKE;
        }

        @Override
        public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
            return kind == CANE;
        }

        @Override
        public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
            return blocks.isEmpty() ? List.of()
                    : List.of(new Evaluation(List.copyOf(blocks.keySet()), blocks.size(), blocks));
        }
    }

    @BeforeEach
    void registerWhatGrows() {
        FakeGrowthRule.register();
        GrowthRules.register(CANE, CaneRule.INSTANCE);
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    @Test
    @DisplayName("it finds what is squarely behind them, which the passive fan never can")
    void aSurveyLooksAllTheWayRound() {
        FakeProbe probe = new FakeProbe();
        probe.placeOak(0, -30); // at their back

        assertTrue(kindsFound(passiveSweep(probe)).isEmpty(),
                "the passive sense only walks the head cone");
        assertTrue(kindsFound(survey(probe)).contains(FakeGrowthRule.THICKET),
                "and turning right round is the whole point of doing this on purpose");
    }

    @Test
    @DisplayName("it finds a cane brake, which no amount of passive looking ever could")
    void aSurveyNoticesWhatNeverStoppedARay() {
        FakeProbe probe = new FakeProbe();
        probe.thin(CANE);
        probe.set(0, 64, 30, CANE); // dead ahead, in plain view, and see-through

        // Not budget or density: a passive ray sails through this cell however many rays are
        // bought — the survey needed a new question, not a bigger allowance.
        assertFalse(kindsFound(passiveSweep(probe)).contains(CaneRule.BRAKE),
                "the passive fan asks only what STOPPED it, and nothing did");
        assertTrue(kindsFound(survey(probe)).contains(CaneRule.BRAKE),
                "a deliberate look asks what it passed through");
    }

    @Test
    @DisplayName("it finishes, and says so — an absence is only worth believing if it looked")
    void aSurveyCompletes() {
        FakeProbe probe = new FakeProbe();
        Survey survey = new Survey(EYED, HERE);

        assertFalse(survey.done(), "a fresh survey has not looked anywhere yet");
        int guard = 0;
        while (!survey.done() && guard++ < 10_000) {
            survey.step(probe, 256, new ArrayList<>());
        }

        assertTrue(survey.done(), "it ran to the end rather than trickling forever");
        org.junit.jupiter.api.Assertions.assertEquals(100, survey.progress());
    }

    private static List<PoiKind> kindsFound(List<SenseEvent> events) {
        return events.stream()
                .filter(e -> e.type() == SenseEvent.Type.GLIMPSED)
                .map(SenseEvent::kind)
                .toList();
    }

    private static List<SenseEvent> survey(FakeProbe probe) {
        Survey survey = new Survey(EYED, HERE);
        List<SenseEvent> events = new ArrayList<>();
        int guard = 0;
        while (!survey.done() && guard++ < 10_000) {
            survey.step(probe, 256, events);
        }
        return events;
    }

    /** The passive tier over the same world, for the comparison each test is really about. */
    private static List<SenseEvent> passiveSweep(FakeProbe probe) {
        HorizonScanner scanner = new HorizonScanner(EYED);
        List<SenseEvent> events = new ArrayList<>();
        for (int tick = 1; tick <= 200; tick++) {
            scanner.step(HERE, AHEAD, tick, probe, 64, events);
        }
        return events;
    }

    /** Inspects to 12, makes out a skyline to 40 — the same body the horizon suite uses. */
    private static final AgentProfile EYED = eyed();

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 12.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 40.0,
                ProfileAspect.PLACES_CONE_DEGREES, 150.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 4.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_surveyor");
        for (ProfileAspect aspect : ProfileAspect.values()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }
}
