package dev.luizloyola.anima.core.agent;

/**
 * What one body is like, resolved — the answer an instinct, a sense or the navigator gets when it
 * asks. Anima names the aspects ({@link ProfileAspect}); who answers them is the body's business.
 * Design: {@code docs/superpowers/specs/2026-07-28-per-species-minds-design.md}.
 *
 * <p>Resolved, not declared: a consumer declares a complete {@link SpeciesProfile} per species, and
 * modifiers it contributes (a trait, a skill, a job) shift an agent's values before they arrive
 * here. "Traits" are one such modifier source, in the consumer's private identity tier.
 *
 * <p>Implementations are live views, not snapshots: an organ may hold one for a body's whole life
 * and still see an {@code /anima config reload} or a job change. The off-thread pathfinder is the
 * exception — it takes a {@link dev.luizloyola.anima.core.nav.MoveCapabilities} snapshot minted
 * here at request time.
 *
 * <p>One abstract method on purpose: everything else derives, so a modifier layer wraps a species
 * view by implementing {@link #raw} alone.
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
}
