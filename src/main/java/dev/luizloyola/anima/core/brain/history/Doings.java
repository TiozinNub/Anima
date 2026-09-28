package dev.luizloyola.anima.core.brain.history;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open registry of doings — {@code SpeechActs}' pattern. Anima declares what its own instincts
 * do; a consumer declares what its work is.
 */
public final class Doings {

    private static final Map<String, Doing> REGISTRY = new LinkedHashMap<>();

    /** Slot: what was run from, as {@link Whom} names it. */
    public static final Doing FLEEING = register(new Doing(
            "fleeing", "anima.doing.fleeing", List.of("from"), true));
    /** Slot: what was fought, as {@link Whom} names it. Recorded when it went down. */
    public static final Doing FIGHTING = register(new Doing(
            "fighting", "anima.doing.fighting", List.of("whom"), true));
    public static final Doing GETTING_UNSTUCK = register(new Doing(
            "getting_unstuck", "anima.doing.getting_unstuck", List.of(), true));
    public static final Doing EATING = register(new Doing(
            "eating", "anima.doing.eating", List.of(), true));
    public static final Doing SORTING_PACK = register(new Doing(
            "sorting_pack", "anima.doing.sorting_pack", List.of(), true));
    /** Not remembered: a chat is not news. */
    public static final Doing TALKING = register(new Doing(
            "talking", "anima.doing.talking", List.of(), false));
    public static final Doing LOOKING_FOR_COMPANY = register(new Doing(
            "looking_for_company", "anima.doing.looking_for_company", List.of(), false));
    /** Not remembered: idling is not news. */
    public static final Doing WANDERING = register(new Doing(
            "wandering", "anima.doing.wandering", List.of(), false));

    /** The slot value for a thing the body never made out. */
    public static final Slot SOMETHING = Slot.lang("anima.doing.something");

    private Doings() {
    }

    public static synchronized Doing register(Doing doing) {
        Doing prior = REGISTRY.putIfAbsent(doing.key(), doing);
        if (prior != null) {
            throw new IllegalStateException("doing '" + doing.key() + "' is already declared");
        }
        return doing;
    }

    public static synchronized Optional<Doing> byKey(String key) {
        return Optional.ofNullable(REGISTRY.get(key));
    }

    public static synchronized Collection<Doing> all() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }
}
