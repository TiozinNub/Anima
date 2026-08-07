package dev.luizloyola.anima.core.agent;

import java.util.List;

/**
 * One agent's own numbers: its species, plus whatever its traits, skills, jobs or injuries are
 * doing to it right now.
 *
 * <p><b>Folded, not walked.</b> These sit on hot paths — the being sense reads several times a tick
 * per body — so the resolved values live in an array, re-folded only when the species view's
 * {@link AgentProfile#version()} (a config reload) or the modifier set's (a job gained or lost)
 * moves. A {@code config reload} still retunes an agent already walking.
 *
 * <p>{@link #of} returns the species view unwrapped when there is nothing to modify: no allocation,
 * no indirection.
 *
 * <p>Not thread-safe. The off-thread pathfinder takes a
 * {@link dev.luizloyola.anima.core.nav.MoveCapabilities} snapshot on the server thread instead.
 */
public final class ModifiedProfile implements AgentProfile {

    private final AgentProfile species;
    private final AgentModifiers modifiers;

    private final double[] resolved = new double[ProfileAspect.count()];
    private long foldedSpeciesVersion = Long.MIN_VALUE;
    private long foldedModifierVersion = Long.MIN_VALUE;
    private long version;

    private ModifiedProfile(AgentProfile species, AgentModifiers modifiers) {
        this.species = species;
        this.modifiers = modifiers;
    }

    /** This agent's profile — the species view itself when there is nothing shifting it. */
    public static AgentProfile of(AgentProfile species, AgentModifiers modifiers) {
        if (modifiers == null || modifiers == AgentModifiers.NONE) {
            return species;
        }
        return new ModifiedProfile(species, modifiers);
    }

    @Override
    public String species() {
        return species.species();
    }

    @Override
    public double raw(ProfileAspect aspect) {
        refoldIfStale();
        return resolved[aspect.index()];
    }

    @Override
    public double base(ProfileAspect aspect) {
        return species.raw(aspect);
    }

    @Override
    public List<AspectModifier> modifiers(ProfileAspect aspect) {
        return modifiers.on(aspect);
    }

    @Override
    public long version() {
        refoldIfStale();
        return version;
    }

    private void refoldIfStale() {
        long speciesVersion = species.version();
        long modifierVersion = modifiers.version();
        if (speciesVersion == foldedSpeciesVersion && modifierVersion == foldedModifierVersion) {
            return;
        }
        foldedSpeciesVersion = speciesVersion;
        foldedModifierVersion = modifierVersion;
        version++;
        for (ProfileAspect aspect : ProfileAspect.all()) {
            resolved[aspect.index()] = resolve(aspect);
        }
    }

    /**
     * Vanilla's attribute algebra, clamped to the aspect's bounds at the end: a modifier is
     * arbitrary consumer arithmetic and could hand back a negative view radius, while every organ
     * downstream assumes its aspect is in bounds.
     */
    private double resolve(ProfileAspect aspect) {
        List<AspectModifier> applied = modifiers.on(aspect);
        double base = species.raw(aspect);
        if (applied.isEmpty()) {
            return base;
        }
        double flat = base;
        double fractionOfBase = 0.0;
        for (AspectModifier modifier : applied) {
            if (modifier.op() == AspectModifier.Op.ADD) {
                flat += modifier.amount();
            } else if (modifier.op() == AspectModifier.Op.ADD_FRACTION_OF_BASE) {
                fractionOfBase += modifier.amount();
            }
        }
        double total = flat * (1.0 + fractionOfBase);
        for (AspectModifier modifier : applied) {
            if (modifier.op() == AspectModifier.Op.ADD_FRACTION_OF_TOTAL) {
                total *= 1.0 + modifier.amount();
            }
        }
        if (!Double.isFinite(total)) {
            return base; // a modifier that produced a NaN says nothing; the species still does
        }
        double bounded = Math.min(aspect.max(), Math.max(aspect.min(), total));
        return aspect.kind() == dev.luizloyola.anima.core.config.KnobSpec.Kind.DOUBLE
                ? bounded
                : Math.rint(bounded);
    }
}
