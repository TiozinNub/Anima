package dev.luizloyola.anima.core.agent;

import java.util.List;

/**
 * What one body is like, resolved — the answer an organ gets when it asks. Anima names the aspects
 * ({@link ProfileAspect}); who answers them is the body's business.
 *
 * <p><b>Why this exists.</b> Anima's tunables were global: every agent saw the same 24-block
 * perception radius out of {@code anima.toml}, where a rabbit's flight distance and a wolf's
 * eyesight are different bodies, not one dial at two settings. Full design:
 * {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md}.
 *
 * <p><b>The resolved read, not the declaration.</b> A consumer declares a {@link SpeciesProfile}
 * per species and contributes modifiers (a trait, a skill, a job) that shift an agent's values;
 * what arrives here is the answer after all of it. "Traits" in this codebase are one such source,
 * belonging to the consumer's private identity tier.
 *
 * <p><b>Read through, every time.</b> Implementations are live views, so an organ may hold one for
 * a body's whole life and still see an {@code /anima config reload} or a job change — except the
 * off-thread pathfinder, which takes a
 * {@link dev.luizloyola.anima.core.nav.MoveCapabilities} snapshot at request time.
 *
 * <p>One abstract method on purpose: a modifier layer implements {@link #raw} alone.
 */
public interface AgentProfile {

    /** Which species' declaration this resolves against — a readout label, never a branch. */
    String species();

    /**
     * This body's value for {@code aspect}, after modifiers. Stored as a double whatever the
     * aspect holds; prefer {@link #i}/{@link #b}/{@link #d} at call sites.
     */
    double raw(ProfileAspect aspect);

    /** A {@link dev.luizloyola.anima.core.config.KnobSpec.Kind#DOUBLE} aspect. */
    default double d(ProfileAspect aspect) {
        return raw(aspect);
    }

    /** An {@link dev.luizloyola.anima.core.config.KnobSpec.Kind#INT} aspect. */
    default int i(ProfileAspect aspect) {
        return (int) raw(aspect);
    }

    /** A {@link dev.luizloyola.anima.core.config.KnobSpec.Kind#BOOL} aspect. */
    default boolean b(ProfileAspect aspect) {
        return raw(aspect) != 0.0;
    }

    /**
     * What this aspect was before anything shifted it — the species' own answer. Equal to
     * {@link #raw} unless something is modifying this agent.
     *
     * <p>Here so a readout can be written once and work on any profile: an unmodified profile
     * answers with an empty middle rather than the readout having to ask what kind of profile it
     * has.
     */
    default double base(ProfileAspect aspect) {
        return raw(aspect);
    }

    /** What is shifting this aspect on this particular agent, in application order. */
    default List<AspectModifier> modifiers(ProfileAspect aspect) {
        return List.of();
    }

    /**
     * Bumped whenever what this profile would answer may have changed — a config reload, a job
     * gained or lost. Something folding these values compares it against what it folded from; a
     * profile with nothing behind it never moves.
     */
    default long version() {
        return 0L;
    }
}
