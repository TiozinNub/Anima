package dev.luizloyola.anima.core.agent.need;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a gauge on a body <em>is</em> — the vocabulary of {@link Needs}, and an extension point.
 *
 * <p><b>Open, not an enum</b>, for the same reason {@code PoiKind} is: whether a body has a gauge
 * for warmth or boredom is a question about the creature a consuming mod is modelling. Anima
 * registers the two it owns; everything else arrives from outside.
 *
 * <p><b>Instances are canonical per key</b> — {@link #register} returns the one instance for a key,
 * so {@code ==} is safe and two mods cannot disagree about what {@code "food"} means.
 *
 * <p>A kind is only a NAME: how the number fills, drains and turns uncomfortable belongs to the
 * {@link Gauge} registered under it.
 */
public final class NeedKind {

    /** Insertion-ordered so listings and saved files are stable between runs. */
    private static final Map<String, NeedKind> REGISTERED = new LinkedHashMap<>();

    /**
     * Hunger — a VIEW over the body's {@code Metabolism}, never a second number (see
     * {@link FoodNeed}). It is a need like any other to everything that enumerates needs, and it
     * is the food organ to everything that eats.
     */
    public static final NeedKind FOOD = register("food");

    /**
     * How much company this body has had lately — the first gauge that is genuinely its own
     * number rather than a view. Bidirectional: see {@link Company}.
     */
    public static final NeedKind COMPANY = register("company");

    private final String key;

    private NeedKind(String key) {
        this.key = key;
    }

    /**
     * Declares a kind of gauge, or returns the existing one when this key is already registered.
     *
     * @param key stable id — what is written to disk and typed into commands, so changing it
     *            forgets the level saved under the old one
     */
    public static synchronized NeedKind register(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a need kind needs a key");
        }
        return REGISTERED.computeIfAbsent(key, NeedKind::new);
    }

    /** The kind with this key, or empty — the read side of the saved-file round trip. */
    public static synchronized Optional<NeedKind> byKey(String key) {
        return Optional.ofNullable(REGISTERED.get(key));
    }

    /** Every registered kind, in registration order. */
    public static synchronized Collection<NeedKind> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(REGISTERED).values());
    }

    /** Stable id — what is written to disk and typed into commands. */
    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return key;
    }
}
