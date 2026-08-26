package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Map<ItemSpec, List<Factory>> REGISTERED = new ConcurrentHashMap<>();

    private Producers() {
    }

    /**
     * Teaches the brain one way to produce {@code spec}. Call during mod initialization; several
     * ways may be registered for the same spec and are offered in registration order, after the
     * always-present "pick one up" method.
     */
    public static void register(ItemSpec spec, Factory producer) {
        REGISTERED.computeIfAbsent(spec, key -> new ArrayList<>()).add(producer);
    }

    /** Whether anybody registered a way to produce {@code spec} — the gate's cheap question. */
    public static boolean knows(ItemSpec spec) {
        return REGISTERED.containsKey(spec);
    }

    /** Whether any registration's spec matches any of {@code ids} — the reachability question. */
    public static boolean knowsAnyOf(java.util.Set<String> ids) {
        for (ItemSpec spec : REGISTERED.keySet()) {
            for (String id : ids) {
                if (spec.matches(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Fresh producer methods for {@code spec}, in registration order; empty when nobody knows. */
    public static List<Method> forSpec(ItemSpec spec) {
        List<Factory> factories = REGISTERED.get(spec);
        if (factories == null) {
            return List.of();
        }
        List<Method> methods = new ArrayList<>(factories.size());
        for (Factory factory : factories) {
            methods.add(factory.create(spec));
        }
        return methods;
    }

    /**
     * Fresh methods from every registration whose spec matches any of {@code ids}, skipping
     * {@code wanted} itself (already offered by identity) — how a crafting ingredient no mod
     * declared reaches a producer. {@code wanted} is also handed to each factory, so a producer
     * registered for "any log" still hears "oak logs" when that is what the goal counts.
     */
    public static List<Method> forItems(java.util.Set<String> ids, ItemSpec wanted) {
        // Matched entries sorted by spec NAME, never map order: a saved plan resumes its method
        // BY INDEX, and this map's iteration order is not stable across JVMs (an ItemSpec's hash
        // includes its lambda).
        java.util.TreeMap<String, List<Factory>> matched = new java.util.TreeMap<>();
        for (Map.Entry<ItemSpec, List<Factory>> entry : REGISTERED.entrySet()) {
            if (entry.getKey() == wanted) {
                continue;
            }
            for (String id : ids) {
                if (entry.getKey().matches(id)) {
                    matched.put(entry.getKey().name(), entry.getValue());
                    break;
                }
            }
        }
        List<Method> methods = new ArrayList<>();
        for (List<Factory> factories : matched.values()) {
            for (Factory factory : factories) {
                methods.add(factory.create(wanted));
            }
        }
        return methods;
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
