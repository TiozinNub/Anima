package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.log.AgentJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seam between a mind and the body wearing it: what a body is like, and who answers. */
class AgentTraitsTest {

    @AfterEach
    void restoreGlobalConfig() {
        Config.reset();
    }

    @Test
    @DisplayName("the configured traits are what every agent read before bodies could differ")
    void configuredMatchesAnimasOwnKnobs() {
        AgentTraits traits = AgentTraits.CONFIGURED;
        assertEquals(Config.get().i(Knob.PEERS_RADIUS), traits.perceptionRadius());
        assertEquals(Config.get().i(Knob.PEERS_CONE_DEGREES), traits.coneDegrees());
        assertEquals(Config.get().i(Knob.PEERS_VERTICAL_DEGREES), traits.verticalHalfDegrees());
        assertEquals(Config.get().d(Knob.PEERS_SNEAK_RANGE_MULT), traits.sneakRangeMult());
    }

    @Test
    @DisplayName("it is a live view, not a snapshot — a reload retunes a body already in the world")
    void configuredReadsThroughToTheStore() {
        AgentTraits held = AgentTraits.CONFIGURED; // as an organ holds it, for the body's whole life
        int before = held.perceptionRadius();

        Config.install(Config.SET.defaults().with(Knob.PEERS_RADIUS, before + 8.0));

        assertEquals(before + 8, held.perceptionRadius(),
                "the same object must see the new configuration; caching it would strand the agent");

        Config.reset();
        assertEquals(before, held.perceptionRadius());
    }

    @Test
    @DisplayName("a brain assembled without a body still has dimensions to read")
    void brainContextDefaultsToTheConfiguredTraits() {
        BrainContext bare = new BrainContext() {
            @Override public ActuatorAccess actuators() {
                return null;
            }

            @Override public Percepts percepts() {
                return null;
            }

            @Override public AgentJournal journal() {
                return null;
            }

            @Override public Pronouns pronouns() {
                return Pronouns.THEY;
            }

            @Override public AgentKnowledge knowledge() {
                return null;
            }

            @Override public double costTolerance() {
                return Double.POSITIVE_INFINITY;
            }
        };

        assertSame(AgentTraits.CONFIGURED, bare.traits(),
                "the default is the bridge — a test rig reads Anima's own values, as it always did");
    }
}
