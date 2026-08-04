package dev.luizloyola.anima.core.brain.knowledge;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a remembered point of interest <em>is</em> — the vocabulary of the knowledge store (see
 * the POI-perception design, 2026-07-23).
 *
 * <p><b>Open, not an enum</b>, because what is worth remembering is a question about the world a
 * consuming mod models: a mod {@link #register}s its own kinds, and the store, the growth
 * machinery and the merge rules never learn their names. Anima registers exactly one —
 * {@link #HERD} — because its being sense produces herd memories directly, without a block-growth
 * rule.
 *
 * <p><b>Canonical per key</b>: {@link #register} returns the one instance for a key and refuses to
 * redefine it, so {@code ==} is safe and two mods cannot disagree about what {@code "tree"} means.
 */
public final class PoiKind {

    /** Insertion-ordered so listings and saved files are stable between runs. */
    private static final Map<String, PoiKind> REGISTERED = new LinkedHashMap<>();

    /**
     * Animals remembered by GENERAL LOCATION: 3+ head of one species is a herd memory
     * (anchor = centroid, {@code units} = head count), 1–2 are individual memories of the same
     * kind — so a brain can weigh two lone cows against a herd of six. {@code detail} carries the
     * species and the merge is detail-aware, so a cow flock never merges into the sheep flock
     * beside it.
     *
     * <p>Merge radius 0: {@code HerdNoter} owns all matching, its expand-recenter rule needing the
     * remembered AREA inflated 2–3× rather than a fixed anchor radius. Herds move; staleness is
     * the right decay.
     */
    public static final PoiKind HERD = register("herd", 0, " head");

    /**
     * Where something frightening was — the durable half of fear. Perception forgets a threat in
     * seconds, which is right for "is it next to me" and wrong afterwards: a body that ran from a
     * creeper should not wander back into that clearing, nor flee a skeleton into where the
     * creeper was. {@code detail} carries the species (or the anonymous-hostile key), so what a
     * fright is worth is looked up live rather than frozen into the memory.
     *
     * <p>Merge radius 0 as for {@link #HERD}: {@code DangerNoter} owns matching, the rule being
     * identity (one memory per thing, moved not duplicated) not distance.
     */
    public static final PoiKind DANGER = register("danger", 0, "", 6_000);

    /**
     * Whether one look at a column's surface settles a rumour of this kind — the only look the
     * near field takes: {@code PoiSensorCore} reads a column's topmost motion-blocking cell and
     * nothing else, then announces "nothing of the sort here". For anything that does not stand at
     * the surface that deletes a true belief, and the far sense sights it again next sweep — the
     * pair churns forever and it is never acted on.
     */
    public enum Settling {
        /** A glance settles it: the thing is the column's surface — trees, lakes, a pumpkin on the ground. */
        SURFACE,
        /**
         * Only a deliberate look settles it; such a rumour survives being walked past. Sugar cane
         * and berry bushes have no collision, so a column probe reads the sand under them, and
         * anything in a cliff face has stone above it.
         */
        DELIBERATE
    }

    private final String key;
    private final int mergeRadius;
    private final String unit;
    private final int lifetimeTicks;
    private final Settling settling;

    private PoiKind(String key, int mergeRadius, String unit, int lifetimeTicks,
            Settling settling) {
        this.key = key;
        this.mergeRadius = mergeRadius;
        this.unit = unit;
        this.lifetimeTicks = lifetimeTicks;
        this.settling = settling;
    }

    /**
     * Declares a kind of place worth remembering, or returns the existing one when this key is
     * already registered with the same shape.
     *
     * @param key         stable id — what is written to disk, so changing it forgets memories
     * @param mergeRadius Chebyshev distance within which two anchors are the same memory;
     *                    {@code note()} replaces rather than accumulates inside it. 0 means only
     *                    an exact anchor matches, for kinds whose noter owns matching itself
     * @param unit        what {@code units} counts, for operator-facing text — {@code " logs"},
     *                    {@code " head"}. Empty when a count would mean nothing
     * @throws IllegalStateException when the key is already registered with a different shape
     */
    public static synchronized PoiKind register(String key, int mergeRadius, String unit) {
        return register(key, mergeRadius, unit, 0, Settling.SURFACE);
    }

    /**
     * The same, for a kind the near field cannot settle from a column's surface — see
     * {@link Settling}. Not saying so leaves the thing sighted by the far sense and deleted by the
     * near one, over and over.
     */
    public static synchronized PoiKind register(String key, int mergeRadius, String unit,
            Settling settling) {
        return register(key, mergeRadius, unit, 0, settling);
    }

    /**
     * The same, for a kind of memory that goes off. Most do not: a remembered grove is right until
     * somebody fells it, so it is corrected by looking rather than by a clock. A remembered fright
     * decays whether or not anybody goes back to check, so it genuinely has a deadline.
     *
     * @param lifetimeTicks how long a memory of this kind stays worth anything, or 0 for a kind
     *     that expires only by being disproven
     */
    public static synchronized PoiKind register(String key, int mergeRadius, String unit,
            int lifetimeTicks) {
        return register(key, mergeRadius, unit, lifetimeTicks, Settling.SURFACE);
    }

    /** The whole shape at once — every other overload is this one with a default filled in. */
    public static synchronized PoiKind register(String key, int mergeRadius, String unit,
            int lifetimeTicks, Settling settling) {
        PoiKind existing = REGISTERED.get(key);
        if (existing != null) {
            if (existing.mergeRadius != mergeRadius || !existing.unit.equals(unit)
                    || existing.lifetimeTicks != lifetimeTicks || existing.settling != settling) {
                throw new IllegalStateException("POI kind \"" + key + "\" is already registered "
                        + "with a different shape — two mods disagree about what it means");
            }
            return existing;
        }
        PoiKind kind = new PoiKind(key, mergeRadius, unit, lifetimeTicks, settling);
        REGISTERED.put(key, kind);
        return kind;
    }

    /** The kind with this key, or empty — the read side of the saved-file round trip. */
    public static synchronized Optional<PoiKind> byKey(String key) {
        return Optional.ofNullable(REGISTERED.get(key));
    }

    /** Every registered kind, in registration order. */
    public static synchronized Collection<PoiKind> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(REGISTERED).values());
    }

    /** Stable id — what is written to disk and typed into commands. */
    public String key() {
        return key;
    }

    /**
     * Chebyshev distance (max per-axis difference) within which two anchors of this kind are the
     * same memory. Covers, for instance, a 2×2 trunk re-seen from another side, or shoreline
     * re-discoveries of one lake.
     */
    public int mergeRadius() {
        return mergeRadius;
    }

    /**
     * How long a memory of this kind stays worth anything, or 0 for a kind that only expires by
     * being disproven. One number for the fade curve and every readout.
     */
    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    /** What {@code units} counts, for operator-facing text ({@code " logs"}, {@code " head"}). */
    public String unit() {
        return unit;
    }

    /** Whether one look at a column's surface settles a rumour of this kind — see {@link Settling}. */
    public Settling settling() {
        return settling;
    }

    @Override
    public String toString() {
        return key;
    }
}
