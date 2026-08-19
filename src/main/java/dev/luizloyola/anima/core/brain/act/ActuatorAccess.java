package dev.luizloyola.anima.core.brain.act;

/**
 * The bundle of actuator ports a task ticks against — handed to {@code PrimitiveTask.tick}/
 * {@code cancel} by the executor each call rather than held: a task owns intent, never the body,
 * and tasks stay constructible in tests with no wiring.
 *
 * <p>Assembled by the mod {@code BrainDriver} over the compat/mod actuator facades. One PORT per
 * actuator domain, never one method per verb: each domain owns its own begin / state / stop
 * lifecycle and this bundle only hands them out. Same shape as {@code Percepts}.
 */
public interface ActuatorAccess {
    /** The legs — see {@link Mover}. */
    Mover mover();

    /** The gullet — see {@link ItemConsumer}. */
    ItemConsumer consumer();

    /** The working arm, breaking — see {@link BlockBreaker}. */
    BlockBreaker breaker();

    /** The working arm, placing — see {@link BlockPlacer}. */
    BlockPlacer placer();

    /** The legs, gaining one block of height — see {@link Riser}. */
    Riser riser();

    /**
     * The eyes — see {@link Gazer}. Defaults to {@link Gazer#NONE}, unlike the ports above: a body
     * that cannot look is only a blind one (every headless rig in the test fixtures), where a mover
     * that cannot move would be a broken body.
     */
    default Gazer gazer() {
        return Gazer.NONE;
    }

    /**
     * The throat — see {@link Voice}. Defaults to {@link Voice#NONE} for the same reason
     * {@link #gazer()} does: a body that cannot call out is only a quiet one.
     */
    default Voice voice() {
        return Voice.NONE;
    }
}
