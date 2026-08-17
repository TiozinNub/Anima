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
 * <p><b>It also declares what it DOES</b> — its {@link Binding}s, so "what does this need drive?"
 * is answered by the registry rather than by reading whichever instinct happens to mention it.
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
     * Where Anima's own needs keep their words. A consumer's needs keep theirs under its own
     * namespace — see {@link Builder#lang}, which is the one thing a mod declaring a need must
     * remember to say.
     */
    private static final String ANIMA_LANG = "anima.needs.";

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
            .drive(Binding.Side.BELOW, "eat")
            .build();

    /**
     * Air — a VIEW over the body's own air supply (see {@link BreathNeed}), and the one need with
     * no organ behind it: the game already ticks air for anything alive, so there is nothing to
     * transcribe and nothing to keep in step.
     *
     * <p><b>The fastest need on the roster by an order of magnitude</b> — a full lungful is fifteen
     * seconds where hunger takes an in-game day — so {@code gasping} is priced to outbid everything
     * else a body could be doing, and both urgent levels are unbounded in what they will spend.
     *
     * <p><b>{@code easy}'s value is the lungful</b>, reported live by the body as its own capacity
     * (see {@code Person.getMaxAirSupply}), so a modifier granting deeper lungs moves both at once
     * and neither can drift from the other.
     *
     * <p>The axis therefore runs well past a settler's own 300: it bounds what any body may
     * DECLARE, not what this one holds. The stretch above a body's {@code easy} is pinned at full
     * pressure like any unanchored end, and is unreachable because air is capped at that capacity.
     */
    public static final NeedKind BREATH = declare("breath", Kind.INT, 0, 1200, "ticks of air")
            .level("drowning", 0, 1.00, -1)
            .level("gasping", 60, 0.95, -1)
            .level("short", 140, 0.35, 40)
            .level("easy", 300, 0.00, 0)
            .build();

    /**
     * How much company this body has had lately — the first gauge that is genuinely its own number
     * rather than a view, and bidirectional: see {@link Company}. Neither end of its axis is
     * comfortable, so both pin at full pressure and the V falls out of the ordinary ramp.
     *
     * <p><b>Both drives are DECLARED and neither is built yet</b> (decision: Luiz, 2026-08-17). The
     * tasks they would propose do not exist — there is no conversation machinery for a lonely body
     * to walk toward, and "go and be alone" is the social spec's v0.1. What company does today it
     * does through {@code Comfort}, which prices where a body would rather stand by
     * {@link Ramp#side}; these two say what will bid once there is something to bid for.
     */
    public static final NeedKind COMPANY = declare("company", Kind.DOUBLE, 0.0, 1.0, "parts of a full day's worth")
            .level("lonely", 0.175, 0.50, -1)
            .level("alone", 0.35, 0.00, 80)
            .level("content", 0.85, 0.00, 0)
            .level("crowded", 1.0, 1.00, 20)
            .drive(Binding.Side.BELOW, "seek_people")
            .drive(Binding.Side.ABOVE, "stray_away")
            .build();

    /**
     * How much of a beating this body can still take — health, less what is dragging it down, plus
     * what is holding it up (see {@link Vigor}). The first need whose value is a composite, which
     * is what makes it the one that proves the {@link Reason} machinery.
     *
     * <p><b>It drives nothing, and its tolerances are zero for that reason</b>: it modulates —
     * declared here so the registry can say so, and weighing nothing yet because there is no fight
     * drive for it to weigh flee against (decision: Luiz, 2026-08-17).
     *
     * <p>The axis runs to vanilla's attribute ceiling rather than to a settler's twenty: it bounds
     * what any body may DECLARE, not what this one has. {@code Vigor} stops reading at whatever
     * {@code healthy} is declared at, so the unanchored stretch above it is unreachable.
     *
     * <p><b>The knees are spread across the whole bar, not bunched at the bottom</b> (decision:
     * Luiz, 2026-08-17). The first cut put {@code dying} at 2 — one heart — which put
     * {@link Severity#CRITICAL} past the point where noticing is any use: on red should mean "the
     * next hit probably kills you", not "already dead". Three hearts is where it means that.
     */
    public static final NeedKind VIGOR = declare("vigor", Kind.DOUBLE, 0.0, 1024.0, "hit points")
            .level("dying", 6, 0.85, 0)
            .level("wounded", 10, 0.60, 0)
            .level("hurt", 15, 0.30, 0)
            .level("healthy", 20, 0.00, 0)
            .modulate("flee_or_fight")
            .build();

    /**
     * The line a source's own reading prints on — {@code "%s is %s"}, filled with what the source
     * is called and the number. Shared by every need, like the three param labels, which is what
     * keeps an itemised readout from costing a string per need per source.
     */
    public static final String REASON_VALUE = ANIMA_LANG + "reason.value";

    /** {@code "%s is %s because:"} — the sentence every itemisation opens with. */
    public static final String REASON_HEADER = ANIMA_LANG + "reason.header";

    private final String key;
    private final Kind kind;
    private final double axisMin;
    private final double axisMax;
    private final String unit;
    private final String lang;
    private final List<NeedLevel> levels;
    private final List<Binding> bindings;
    private final Ramp ramp;

    private NeedKind(String key, Kind kind, double axisMin, double axisMax, String unit,
            String lang, List<NeedLevel> levels, List<Binding> bindings) {
        this.key = key;
        this.kind = kind;
        this.axisMin = axisMin;
        this.axisMax = axisMax;
        this.unit = unit;
        this.lang = lang;
        this.levels = List.copyOf(levels);
        this.bindings = List.copyOf(bindings);
        this.ramp = levels.isEmpty() ? null : new Ramp(this.levels, axisMin, axisMax);
        for (NeedLevel level : this.levels) {
            level.attach(this);
        }
        for (Binding binding : this.bindings) {
            binding.attach(this);
        }
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
        // A gauge reads somewhere on an axis and its levels ramp between them; text has no ramp.
        if (kind == Kind.STRING) {
            throw new IllegalArgumentException(
                    "need \"" + key + "\" cannot hold text — a gauge reads a point on an axis");
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
                new NeedKind(key, Kind.DOUBLE, 0.0, 1.0, "", ANIMA_LANG + key,
                        List.of(), List.of()));
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

    /**
     * Where this need's words live — {@code anima.needs.hunger}, and a consumer's own namespace for
     * a need it declared. The levels hang their names off it; see {@link NeedLevel#nameKey()}.
     */
    public String lang() {
        return lang;
    }

    /** What to call this need to a reader: {@code anima.needs.vigor.name} → "Vigor". */
    public String nameKey() {
        return lang + ".name";
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

    /**
     * What this need does to behaviour — its drives and its modulators, in declaration order, and
     * empty for a need that does nothing but be readable. See {@link Binding}.
     */
    public List<Binding> bindings() {
        return bindings;
    }

    /**
     * One binding by key, for whoever supplies what it proposes ({@code NeedDrive}). Throws for a
     * key this need never declared, rather than returning empty: a drive bound to nothing is a
     * typo, and a silent one leaves a body with a want it can never act on.
     */
    public Binding binding(String bindingKey) {
        for (Binding binding : bindings) {
            if (binding.key().equals(bindingKey)) {
                return binding;
            }
        }
        throw new IllegalArgumentException(key + " declares no binding \"" + bindingKey + "\"");
    }

    /**
     * One level by name, for the rare caller that means a particular one rather than whichever the
     * body is at — {@code BREATH.level("easy")}, whose value a body reports as its own lung
     * capacity. Empty for a name this need never declared, so a rename fails where it is used
     * instead of silently reading zero.
     */
    public Optional<NeedLevel> level(String levelKey) {
        return levels.stream().filter(level -> level.key().equals(levelKey)).findFirst();
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
        private final List<Binding> bindings = new ArrayList<>();
        private String lang;
        private NeedKind pending;

        private Builder(String key, Kind kind, double axisMin, double axisMax, String unit) {
            this.key = key;
            this.kind = kind;
            this.axisMin = axisMin;
            this.axisMax = axisMax;
            this.unit = unit;
            this.lang = ANIMA_LANG + key;
        }

        /**
         * Where this need's words live, if not under Anima's own namespace — {@code
         * "fidelia.needs.boredom"}. Anima cannot work this out: it does not know which mod is
         * calling, and defaulting to its own namespace would have a consumer's need read as a
         * missing Anima string in every language.
         */
        public Builder lang(String root) {
            this.lang = Objects.requireNonNull(root, "root");
            return this;
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

        /**
         * Something this need wants done, and which end of it wants that. The task is supplied
         * separately by whoever owns it ({@code NeedDrive}) — see {@link Binding}.
         *
         * @param side which end of the need bids through this: below comfort, above it, or either
         * @param bindingKey stable id, and the drive's {@code Instinct.key()}
         */
        public Builder drive(Binding.Side side, String bindingKey) {
            bindings.add(new Binding(key, Binding.Verb.DRIVE, side, bindingKey));
            return this;
        }

        /**
         * Something this need weighs in on without ever proposing it. Sideless: a modulator
         * contributes to somebody else's decision rather than asking for anything of its own.
         */
        public Builder modulate(String bindingKey) {
            bindings.add(new Binding(key, Binding.Verb.MODULATE,
                    Binding.Side.EITHER, bindingKey));
            return this;
        }

        /** The declared need, canonical per key. */
        public synchronized NeedKind build() {
            NeedKind existing = REGISTERED.get(key);
            if (existing != null) {
                return existing;
            }
            return put(new NeedKind(key, kind, axisMin, axisMax, unit, lang, levels, bindings));
        }

        /** A stand-in the levels and bindings can name while they are still being collected. */
        private NeedKind pending() {
            if (pending == null) {
                pending = new NeedKind(key, kind, axisMin, axisMax, unit, lang, List.of(), List.of());
            }
            return pending;
        }
    }
}
