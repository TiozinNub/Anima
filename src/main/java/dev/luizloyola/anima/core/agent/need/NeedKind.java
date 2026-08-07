package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.SpeciesProfile;
import dev.luizloyola.anima.core.config.KnobSpec.Kind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a gauge on a body <em>is</em> — the vocabulary of {@link Needs}, and an extension point.
 *
 * <p><b>Open, not an enum</b>, for the same reason {@code PoiKind} is: whether a body has a gauge
 * for warmth or for boredom is a question about the creature a consuming mod is modelling.
 *
 * <p><b>A need declares its LEVELS, and the levels are where the numbers live.</b> Where a species
 * crosses each level, how hard it wants something done there, and what it will spend are three
 * per-species aspects apiece, generated here and edited in a config file. See
 * {@code docs/superpowers/specs/2026-08-06-needs-design.md}.
 *
 * <p><b>Declare at class-initialisation time.</b> The aspects a level generates must exist before
 * any species is declared, or that species is complete without them — {@link ProfileAspect} freezes
 * at the first {@link SpeciesProfile.Builder#build()}. Anima's own are forced into existence from
 * there via {@link #ensureDeclared()}; a consumer's own belong in its mod init.
 *
 * <p><b>Canonical per key.</b> {@link #declare} returns the one instance for a key, so {@code ==}
 * is safe.
 */
public final class NeedKind {

    /** Insertion-ordered so listings and saved files are stable between runs. */
    private static final Map<String, NeedKind> REGISTERED = new LinkedHashMap<>();

    /**
     * Hunger — a VIEW over the body's {@code Metabolism}, never a second number (see
     * {@link FoodNeed}). The ramp through its levels is the {@code 1 - food/20} the brain
     * has always read: {@code sated} anchors the full bar at no pressure, the empty end pins at
     * full, and the three corners between are collinear with both.
     */
    public static final NeedKind HUNGER = declare("hunger", Kind.INT, 0, 20, "food points")
            .level("starving", 3, 0.85, -1)
            .level("hungry", 8, 0.60, 60)
            .level("peckish", 14, 0.30, 15)
            .level("sated", 20, 0.00, 0)
            .build();

    /**
     * How much company this body has had lately — the first gauge that is genuinely its own number
     * rather than a view, and bidirectional: see {@link Company}. Neither end of its axis is
     * comfortable, so both pin at full pressure and the V falls out of the ordinary ramp.
     */
    public static final NeedKind COMPANY = declare("company", Kind.DOUBLE, 0.0, 1.0, "parts of a full day's worth")
            .level("desolate", 0.175, 0.50, -1)
            .level("alone", 0.35, 0.00, 80)
            .level("content", 0.85, 0.00, 0)
            .level("crowded", 1.0, 1.00, 20)
            .build();

    private final String key;
    private final Kind kind;
    private final double axisMin;
    private final double axisMax;
    private final String unit;
    private final List<NeedLevel> levels;
    private final Ramp ramp;

    private NeedKind(String key, Kind kind, double axisMin, double axisMax, String unit,
            List<NeedLevel> levels) {
        this.key = key;
        this.kind = kind;
        this.axisMin = axisMin;
        this.axisMax = axisMax;
        this.unit = unit;
        this.levels = List.copyOf(levels);
        this.ramp = levels.isEmpty() ? null : new Ramp(this.levels, axisMin, axisMax);
    }

    /**
     * Starts declaring a kind of gauge and the axis its value lives on.
     *
     * @param key stable id — what is written to disk, typed into commands, and the middle segment
     *     of every aspect its levels generate, so changing it orphans a species' tuning
     * @param kind whether the value is whole (food points) or continuous
     * @param axisMin the least this need's source can read, and one end of its ramp
     * @param axisMax the most it can read, and the other end
     * @param unit what the value is counted in, for generated documentation
     */
    public static synchronized Builder declare(String key, Kind kind, double axisMin,
            double axisMax, String unit) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a need kind needs a key");
        }
        return new Builder(key, kind, axisMin, axisMax, unit);
    }

    /**
     * A need with no levels of its own, whose gauge answers for its own pressure. Registers no
     * aspects, so unlike {@link #declare} it is safe to call at any time.
     */
    public static synchronized NeedKind register(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a need kind needs a key");
        }
        NeedKind existing = REGISTERED.get(key);
        return existing != null ? existing : put(
                new NeedKind(key, Kind.DOUBLE, 0.0, 1.0, "", List.of()));
    }

    /**
     * Forces Anima's own needs into existence, so their generated aspects are registered before any
     * species is declared. Called from {@link SpeciesProfile.Builder#build()}: nothing else
     * references this class early enough.
     */
    public static void ensureDeclared() {
        // Touching any constant runs this class's initialiser. That is the whole job.
        Objects.requireNonNull(HUNGER);
    }

    /**
     * Every level aspect of every declared need, with the value its need declared for it.
     *
     * <p>{@link SpeciesProfile.Builder#build()} fills these in for a species that did not mention
     * them — the one place a declaration may be incomplete. These are the NEED's own declaration of
     * what its words mean, as {@code PoiKind.HERD} ships a merge radius, and any species may
     * disagree with a line in a config file.
     */
    public static synchronized Map<ProfileAspect, Double> levelDefaults() {
        Map<ProfileAspect, Double> defaults = new LinkedHashMap<>();
        for (NeedKind need : REGISTERED.values()) {
            for (NeedLevel level : need.levels) {
                defaults.putAll(level.defaults());
            }
        }
        return defaults;
    }

    private static NeedKind put(NeedKind kind) {
        REGISTERED.put(kind.key, kind);
        return kind;
    }

    /** The kind with this key, or empty — the read side of the saved-file round trip. */
    public static synchronized Optional<NeedKind> byKey(String key) {
        return Optional.ofNullable(REGISTERED.get(key));
    }

    /** Every declared kind, in declaration order. */
    public static synchronized Collection<NeedKind> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(REGISTERED).values());
    }

    /** Stable id — what is written to disk and typed into commands. */
    public String key() {
        return key;
    }

    /** Whether this need's value is whole or continuous. */
    public Kind kind() {
        return kind;
    }

    /** What the value is counted in, for generated docs and readouts. */
    public String unit() {
        return unit;
    }

    public double axisMin() {
        return axisMin;
    }

    public double axisMax() {
        return axisMax;
    }

    /** This need's levels, in declaration order. Empty for a need that answers for itself. */
    public List<NeedLevel> levels() {
        return levels;
    }

    /** The ramp through those levels, or null when there are none. */
    public Ramp ramp() {
        return ramp;
    }

    @Override
    public String toString() {
        return key;
    }

    /** Collects a need's levels, generating three aspects apiece. */
    public static final class Builder {

        private final String key;
        private final Kind kind;
        private final double axisMin;
        private final double axisMax;
        private final String unit;
        private final List<NeedLevel> levels = new ArrayList<>();
        private NeedKind pending;

        private Builder(String key, Kind kind, double axisMin, double axisMax, String unit) {
            this.key = key;
            this.kind = kind;
            this.axisMin = axisMin;
            this.axisMax = axisMax;
            this.unit = unit;
        }

        /**
         * One named step, with the species defaults behind its three aspects.
         *
         * @param name what this level is called — a config path segment and a lang key
         * @param value the boundary at which a body becomes this level, in the need's own units
         * @param pressure the ramp's corner at that boundary, {@code 0..1}
         * @param tolerance what a body here will spend, in walk blocks; negative is unbounded
         */
        public Builder level(String name, double value, double pressure, double tolerance) {
            levels.add(new NeedLevel(pending(), name, axisMin, axisMax, value, pressure, tolerance));
            return this;
        }

        /** The declared need, canonical per key. */
        public synchronized NeedKind build() {
            NeedKind existing = REGISTERED.get(key);
            if (existing != null) {
                return existing;
            }
            return put(new NeedKind(key, kind, axisMin, axisMax, unit, levels));
        }

        /** A stand-in the levels can name while they are still being collected. */
        private NeedKind pending() {
            if (pending == null) {
                pending = new NeedKind(key, kind, axisMin, axisMax, unit, List.of());
            }
            return pending;
        }
    }
}
