package dev.luizloyola.anima.core.brain.sense;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How frightening each kind of thing is, to one kind of body — resolved through whoever is
 * perceiving, not one table the world shares: a wolf and a settler disagree about a sheep.
 *
 * <ul>
 *   <li><b>Derived</b> is machine-written from the entity registry on every world load and
 *       regenerated wholesale, so a mod's mobs appear and an uninstalled mod's entries do not
 *       linger. An operator editing here is editing something that will be overwritten.
 *   <li><b>Overrides</b> are written by a person and never touched by the generator, which keeps
 *       "somebody chose 2.0" distinguishable from "the generator guessed 2.0".
 * </ul>
 *
 * <p>Closed schema is mandatory and complete; open registry-keyed tables are derived with explicit
 * overrides — which is why this is neither a {@code ProfileAspect} nor a {@code Knob}: entity ids
 * are an open set.
 *
 * <p>Weights are unsigned, {@code 0} meaning "not frightening", never "food". Immutable and pure
 * core; building one from a registry is the mod layer's job.
 */
public final class DangerTable {

    /** The weight anything the table does not name falls back to. */
    public static final String DEFAULT_KEY = "default";

    /**
     * The weight for something known to be hostile but not identified — a body shot from cover,
     * with nothing but a direction.
     *
     * <p>A key of its own because {@link #DEFAULT_KEY} means "a mob I have no opinion about":
     * without it, an agent stands calmly in arrow fire.
     */
    public static final String HOSTILE_KEY = "unknown_hostile";

    /** A table with nothing in it — everything falls to {@link #DEFAULT_KEY}, which is 1.0. */
    public static final DangerTable NEUTRAL = new DangerTable(Map.of(), Map.of(), Set.of());

    private final Map<String, Double> derived;
    private final Map<String, Double> overrides;
    private final Set<String> ranged;

    public DangerTable(Map<String, Double> derived, Map<String, Double> overrides,
            Set<String> ranged) {
        this.derived = Map.copyOf(derived);
        this.overrides = Map.copyOf(overrides);
        this.ranged = Set.copyOf(ranged);
    }

    /**
     * How frightening this species is to the body reading the table: its override if a person
     * wrote one, the generator's guess if not, and the default if neither knows it.
     */
    public double weight(String species) {
        Double override = overrides.get(species);
        if (override != null) {
            return override;
        }
        Double guess = derived.get(species);
        if (guess != null) {
            return guess;
        }
        Double fallback = overrides.get(DEFAULT_KEY);
        return fallback != null ? fallback : derived.getOrDefault(DEFAULT_KEY, 1.0);
    }

    /**
     * Whether this species attacks from range whatever it is visibly holding — a heard-only
     * skeleton still shoots from where it stands.
     *
     * <p>A property of the SHOOTER, so it is the same answer for every body that perceives it,
     * which is why it travels with the table rather than being a per-species judgment.
     */
    public boolean ranged(String species) {
        return ranged.contains(species);
    }

    /** The generator's half — regenerated wholesale on every world load. */
    public Map<String, Double> derived() {
        return derived;
    }

    /** The half a person wrote, which the generator never touches. */
    public Map<String, Double> overrides() {
        return overrides;
    }

    /** Every species this table knows about, either half, ordered overrides-last. */
    public Map<String, Double> resolved() {
        Map<String, Double> all = new LinkedHashMap<>(derived);
        all.putAll(overrides);
        return Collections.unmodifiableMap(all);
    }

    /** This table with a freshly generated derived half — what a world load produces. */
    public DangerTable withDerived(Map<String, Double> regenerated) {
        return new DangerTable(regenerated, overrides, ranged);
    }

    /** This table with different overrides — what reading the file produces. */
    public DangerTable withOverrides(Map<String, Double> replacements) {
        return new DangerTable(derived, replacements, ranged);
    }

    /** This table knowing about more shooters — what a consumer's registration produces. */
    public DangerTable withRanged(Set<String> shooters) {
        return new DangerTable(derived, overrides, shooters);
    }

    @Override
    public String toString() {
        return "DangerTable(" + derived.size() + " derived, " + overrides.size() + " overrides)";
    }
}
