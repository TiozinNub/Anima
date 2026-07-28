package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BlockPlacer;
import dev.luizloyola.anima.core.brain.act.Scaffolder;
import dev.luizloyola.anima.core.brain.board.AgentClaims;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.act.ItemConsumer;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.agent.AgentId;

/**
 * The test {@link BrainContext}: everything a task or method can reach, scripted and inspectable.
 * Mirrors what the mod {@code BrainDriver} assembles over a live Person, minus Minecraft.
 */
final class FakeContext implements BrainContext {
    final FakeMover mover = new FakeMover();
    final FakeConsumer consumer = new FakeConsumer();
    final FakeBreaker breaker = new FakeBreaker();
    final FakePlacer placer = new FakePlacer();
    final FakeScaffolder scaffolder = new FakeScaffolder();
    final FakePercepts percepts = new FakePercepts();
    /** A real knowledge store (pure and headless anyway) — chop tests seed and inspect it. */
    final AgentKnowledge knowledge = new AgentKnowledge();
    /** This fake person's identity — what its claims are held under. */
    final AgentId self = AgentId.random();
    /** The narrating voice behind journal pronouns — settable so a test can assert either one. */
    Pronouns pronouns = Pronouns.of("she", "her", "her");
    /**
     * A real claim registry (pure anyway), private by default so solo tests behave as before;
     * contention tests point two contexts at one shared instance to simulate a settlement.
     */
    SiteClaims siteClaims = new SiteClaims();
    /**
     * A real (in-memory) journal on a fixed-tick clock, so a narrating task records somewhere
     * inspectable ({@code journalService.recent(...)}). Bound to one throwaway person.
     */
    final JournalService journalService = new JournalService(() -> 0L);
    private final AgentJournal journal = journalService.forPerson(AgentId.random());
    /**
     * The cost ceiling the executor gates methods by (raw food is priced out at 60, admitted at
     * ∞). Defaults to ∞, so tests predating cost tolerance see every applicable method.
     */
    double costTolerance = Double.POSITIVE_INFINITY;
    private final ActuatorAccess actuators = new ActuatorAccess() {
        @Override
        public Mover mover() {
            return mover;
        }

        @Override
        public ItemConsumer consumer() {
            return consumer;
        }

        @Override
        public BlockBreaker breaker() {
            return breaker;
        }

        @Override
        public BlockPlacer placer() {
            return placer;
        }

        @Override
        public Scaffolder scaffolder() {
            return scaffolder;
        }
    };

    @Override
    public ActuatorAccess actuators() {
        return actuators;
    }

    @Override
    public Percepts percepts() {
        return percepts;
    }

    @Override
    public AgentJournal journal() {
        return journal;
    }

    @Override
    public Pronouns pronouns() {
        return pronouns;
    }

    @Override
    public AgentKnowledge knowledge() {
        return knowledge;
    }

    @Override
    public AgentClaims claims() {
        return siteClaims.forPerson(self);
    }

    @Override
    public double costTolerance() {
        return costTolerance;
    }
}
