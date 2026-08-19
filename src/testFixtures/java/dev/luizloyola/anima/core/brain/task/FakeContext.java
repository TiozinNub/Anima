package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BlockPlacer;
import dev.luizloyola.anima.core.brain.board.AgentClaims;
import dev.luizloyola.anima.core.brain.board.SiteClaims;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.act.ItemConsumer;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.brain.act.Riser;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.TestDanger;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.agent.AgentId;

/**
 * The test {@link BrainContext}: everything a task or method can reach, scripted and inspectable.
 * Mirrors what the mod {@code BrainDriver} assembles over a live Person, minus Minecraft.
 */
public final class FakeContext implements BrainContext {
    public final FakeMover mover = new FakeMover();
    public final FakeConsumer consumer = new FakeConsumer();
    public final FakeBreaker breaker = new FakeBreaker();
    public final FakePlacer placer = new FakePlacer();
    public final FakeRiser riser = new FakeRiser();
    public final FakeGazer gazer = new FakeGazer();
    public final FakePercepts percepts = new FakePercepts();
    /** The throat, marking into the percepts' own {@code called} set exactly as a live one does. */
    public final FakeVoice voice = new FakeVoice(percepts.called);
    /** A real knowledge store (pure and headless anyway) — task tests seed and inspect it. */
    public final AgentKnowledge knowledge = new AgentKnowledge();
    /** This fake person's identity — what its claims are held under. */
    public final AgentId self = AgentId.random();
    /** The narrating voice behind journal pronouns — settable so a test can assert either one. */
    public Pronouns pronouns = Pronouns.of("she", "her", "her");
    /** What the fake body is like — {@code TestSpecies.with(aspect, value)} makes a variant. */
    public AgentProfile profile = TestSpecies.PROFILE;
    /** What the fake body is afraid of. Settable, for a test about a body with other fears. */
    public DangerTable danger = TestDanger.TABLE;
    /**
     * A real claim registry (pure anyway), private by default so solo tests behave as before;
     * contention tests point two contexts at one shared instance to simulate a settlement.
     */
    public SiteClaims siteClaims = new SiteClaims();
    /**
     * A real (in-memory) journal on a fixed-tick clock, so a narrating task records somewhere
     * inspectable ({@code journalService.recent(...)}). Bound to one throwaway person.
     */
    public final JournalService journalService = new JournalService(() -> 0L);
    private final AgentJournal journal = journalService.forPerson(AgentId.random());
    /**
     * The cost ceiling the executor gates methods by (raw food is priced out at 60, admitted at
     * ∞). Defaults to ∞, so tests predating cost tolerance see every applicable method.
     */
    public double costTolerance = Double.POSITIVE_INFINITY;
    /**
     * Whether the body is mid-operation on the structural blocks around it — see
     * {@link dev.luizloyola.anima.core.brain.task.Task#reshapesGround()}. Settable because a fake
     * context has no executor to derive it from.
     */
    public boolean reshapingGround = false;
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
        public Riser riser() {
            return riser;
        }

        @Override
        public dev.luizloyola.anima.core.brain.act.Gazer gazer() {
            return gazer;
        }

        @Override
        public dev.luizloyola.anima.core.brain.act.Voice voice() {
            return voice;
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
    public AgentProfile profile() {
        return profile;
    }

    @Override
    public DangerTable danger() {
        return danger;
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
    public boolean reshapingGround() {
        return reshapingGround;
    }

    @Override
    public double costTolerance() {
        return costTolerance;
    }

    /** The body's stream. Fixed by default so a test that draws twice gets the same two numbers
     *  every run; {@link #seed} pins it where a test cares which numbers those are. */
    private java.util.random.RandomGenerator random =
            new dev.luizloyola.anima.core.agent.AgentRandom(20260805L);

    /** Pins this context's stream — the replacement for handing a task its own generator. */
    public FakeContext seed(java.util.random.RandomGenerator random) {
        this.random = random;
        return this;
    }

    @Override
    public java.util.random.RandomGenerator random() {
        return random;
    }
}
