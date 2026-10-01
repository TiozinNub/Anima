package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Who knows how to <em>make more</em> of a thing — the registry {@link ObtainItem} consults when
 * picking one up off the floor is not enough. Where items come from is the consuming mod's
 * question, not the library's; a spec with no registered producer is legitimate, not an error,
 * and {@code ObtainItem} degrades to scavenging.
 *
 * <p><b>Registered by identity, not by name:</b> the key is the {@link ItemSpec} instance the
 * consumer declares, so two mods can want different things by the same name without colliding.
 *
 * <p>Producers are supplied as factories because a {@link Method} is stateful once it starts —
 * each {@code ObtainItem} needs its own.
 */
public final class Producers {

    /** How to build a producer, told what the goal actually wants. */
    @FunctionalInterface
    public interface Factory {
        /**
         * A fresh producer for this goal. {@code wanted} is the spec the GOAL carries, which may be
         * narrower than the one this producer was registered under — registered for "any log",
         * asked for "oak logs".
         */
        Method create(ItemSpec wanted);
    }

    /** One way to produce, and the items it can ever make. */
    private record Registration(Predicate<String> yields, Factory factory) {
    }

    private static final Map<ItemSpec, List<Registration>> REGISTERED = new ConcurrentHashMap<>();

    private Producers() {
    }

    /**
     * Teaches the brain one way to produce {@code spec}. Call during mod initialization; several
     * ways may be registered for the same spec and are offered in registration order, after the
     * always-present "pick one up" method.
     *
     * <p>{@code yields} is what the way can ever make, which a family spec overstates: forage
     * registered under "ready food" told every recipe that dried kelp and beetroot could be had,
     * and the only way left at the bottom was a store, looked in again every recheck (in-world,
     * 2026-09-30). Only ids both match.
     */
    public static void register(ItemSpec spec, Predicate<String> yields, Factory producer) {
        REGISTERED.computeIfAbsent(spec, key -> new ArrayList<>())
                .add(new Registration(id -> spec.matches(id) && yields.test(id), producer));
    }

    /** Whether anybody registered a way to produce {@code spec} — the gate's cheap question. */
    public static boolean knows(ItemSpec spec) {
        return REGISTERED.containsKey(spec);
    }

    /** Whether some registered way can make any of {@code ids} — the reachability question. */
    public static boolean knowsAnyOf(java.util.Set<String> ids) {
        for (List<Registration> ways : REGISTERED.values()) {
            for (Registration way : ways) {
                if (ids.stream().anyMatch(way.yields())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fresh producer methods for {@code spec}, in registration order; empty when nobody knows. */
    public static List<Method> forSpec(ItemSpec spec) {
        List<Registration> ways = REGISTERED.get(spec);
        if (ways == null) {
            return List.of();
        }
        List<Method> methods = new ArrayList<>(ways.size());
        for (Registration way : ways) {
            methods.add(way.factory().create(spec));
        }
        return methods;
    }

    /**
     * Fresh methods from every registered way that can make any of {@code ids}, skipping
     * {@code wanted}'s own (already offered by identity) — how a crafting ingredient no mod
     * declared reaches a producer. {@code wanted} is also handed to each factory, so a producer
     * registered for "any log" still hears "oak logs" when that is what the goal counts. Asks
     * what {@link #knowsAnyOf} asks, so reachability never promises a way this does not offer.
     */
    public static List<Method> forItems(java.util.Set<String> ids, ItemSpec wanted) {
        // Matched entries sorted by spec NAME, never map order: a saved plan resumes its method
        // BY INDEX, and this map's iteration order is not stable across JVMs (an ItemSpec's hash
        // includes its lambda).
        java.util.TreeMap<String, List<Registration>> matched = new java.util.TreeMap<>();
        for (Map.Entry<ItemSpec, List<Registration>> entry : REGISTERED.entrySet()) {
            if (entry.getKey() == wanted) {
                continue;
            }
            List<Registration> making = entry.getValue().stream()
                    .filter(way -> ids.stream().anyMatch(way.yields())).toList();
            if (!making.isEmpty()) {
                matched.put(entry.getKey().name(), making);
            }
        }
        List<Method> methods = new ArrayList<>();
        for (List<Registration> ways : matched.values()) {
            for (Registration way : ways) {
                methods.add(way.factory().create(wanted));
            }
        }
        return methods;
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
