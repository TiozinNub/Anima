package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.FakeGrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRules;
import dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A look round finishes, files what it sighted as glimpses, and a restored one turns on from the
 * bearing it had reached.
 */
class LookRoundTest {

    @BeforeEach
    void registerWhatGrows() {
        FakeGrowthRule.register();
    }

    @AfterEach
    void forgetWhatGrows() {
        GrowthRules.reset();
    }

    @Test
    void aLookRoundFindsWhatIsBehind() {
        FakeContext ctx = new FakeContext();
        ctx.profile = eyed();
        ctx.percepts.blocks.placeOak(0, -30); // at their back
        LookRound look = new LookRound();

        TaskStatus status = TaskStatus.RUNNING;
        for (int tick = 0; tick < 200 && status == TaskStatus.RUNNING; tick++) {
            status = look.tick(ctx);
        }

        assertEquals(TaskStatus.SUCCESS, status);
        assertFalse(ctx.knowledge.glimpses(FakeGrowthRule.THICKET).isEmpty());
        assertEquals(HorizonBuffer.BINS, look.bearing());
    }

    @Test
    void aRestoredLookTurnsOnFromItsBearing() {
        FakeContext ctx = new FakeContext();
        ctx.profile = eyed();
        ctx.percepts.blocks.placeOak(0, -30);
        LookRound done = new LookRound(HorizonBuffer.BINS);

        assertEquals(TaskStatus.SUCCESS, done.tick(ctx), "a look already made is done");
        assertTrue(ctx.knowledge.glimpses(FakeGrowthRule.THICKET).isEmpty(),
                "and looks down no bearing again");
    }

    private static AgentProfile eyed() {
        Map<ProfileAspect, Double> overrides = Map.of(
                ProfileAspect.PLACES_RADIUS, 12.0,
                ProfileAspect.PLACES_HORIZON_RADIUS, 40.0,
                ProfileAspect.PLACES_CONE_DEGREES, 150.0,
                ProfileAspect.PLACES_NEAR_RADIUS, 4.0,
                ProfileAspect.BODY_HEIGHT, 2.0);
        SpeciesProfile.Builder builder = SpeciesProfile.of("test_look_round");
        for (ProfileAspect aspect : ProfileAspect.all()) {
            builder.set(aspect, overrides.getOrDefault(aspect, TestSpecies.BIPED.get(aspect)));
        }
        return builder.build().fixed();
    }
}
