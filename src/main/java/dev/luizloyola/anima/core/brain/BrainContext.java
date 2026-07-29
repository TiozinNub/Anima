package dev.luizloyola.anima.core.brain;

import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.board.AgentClaims;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Pronouns;

/**
 * Everything the task machinery is handed per call: the body's controls ({@link #actuators()}) and
 * the brain's senses ({@link #percepts()}), bundled so one contract serves the whole tree — the
 * executor hands the same context to every call.
 *
 * <p>Assembled per Person by the mod {@code BrainDriver}: fresh views over live state, never a
 * snapshot. Passed into every call rather than held — a task owns intent, never the body.
 */
public interface BrainContext {
    /** The body's controls — one port per actuator domain (legs, gullet, ...). */
    ActuatorAccess actuators();

    Percepts percepts();

    /**
     * This person's debug journal ({@link AgentJournal}), narrated to by tasks and instincts.
     * Recording cannot affect the simulation, so a task may write freely; bound to the
     * {@code AgentId}, so an offline-simulated person with no entity logs the same way.
     */
    AgentJournal journal();

    /**
     * The one place a core task may get a pronoun from: a journal line reaches a player's chat
     * through the thought broadcast, so a hardcoded "her" misgenders half the settlement.
     */
    Pronouns pronouns();

    /**
     * What the body running this brain is like — the one place a core instinct or task may get an
     * aspect from, for the same reason as {@link #pronouns()}: a hardcoded 24-block radius is right
     * for a settler and wrong for a rabbit. Defaults to {@link AgentProfile#CONFIGURED}, so a
     * context assembled without a body (tests, minimal rigs) reads Anima's configured values.
     */
    default AgentProfile profile() {
        return AgentProfile.CONFIGURED;
    }

    /**
     * This person's remembered POIs — memory rather than perception, and the same object the
     * "notice as you go" sensor fills, so a task's {@code forget(...)} is read by every method
     * pricing staleness with {@code nearest(...)}/{@code age(...)}. Durable and
     * {@code AgentId}-keyed: it outlives the entity.
     */
    AgentKnowledge knowledge();

    /**
     * The shared work-site claims through this person's eyes ({@link AgentClaims}): a method skips
     * sites that are not available, a working task heartbeats its site every tick and releases it
     * on every exit. Defaults to {@link AgentClaims#SOLO} — a rig with no shared registry is a
     * group of one.
     */
    default AgentClaims claims() {
        return AgentClaims.SOLO;
    }

    /**
     * The maximum method cost currently acceptable, in the walk-block currency methods price
     * themselves in — a costlier applicable method is treated as inapplicable. Set by the arbiter
     * from the active instinct's pressure through {@link ToleranceCurve};
     * {@link Double#POSITIVE_INFINITY} is unbounded — the STARVING plateau, and manual driving.
     */
    double costTolerance();
}
