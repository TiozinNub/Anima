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
    public static final PoiKind DANGER = register("danger", 0, "");

    private final String key;
    private final int mergeRadius;
    private final String unit;

    private PoiKind(String key, int mergeRadius, String unit) {
        this.key = key;
        this.mergeRadius = mergeRadius;
        this.unit = unit;
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
        PoiKind existing = REGISTERED.get(key);
        if (existing != null) {
            if (existing.mergeRadius != mergeRadius || !existing.unit.equals(unit)) {
                throw new IllegalStateException("POI kind \"" + key + "\" is already registered "
                        + "with a different shape — two mods disagree about what it means");
            }
            return existing;
        }
        PoiKind kind = new PoiKind(key, mergeRadius, unit);
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

    /** What {@code units} counts, for operator-facing text ({@code " logs"}, {@code " head"}). */
    public String unit() {
        return unit;
    }

    @Override
    public String toString() {
        return key;
    }
}
