package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

    private static final Map<ItemSpec, List<Supplier<Method>>> REGISTERED = new ConcurrentHashMap<>();

    private Producers() {
    }

    /**
     * Teaches the brain one way to produce {@code spec}. Call during mod initialization; several
     * ways may be registered for the same spec and are offered in registration order, after the
     * always-present "pick one up" method.
     */
    public static void register(ItemSpec spec, Supplier<Method> producer) {
        REGISTERED.computeIfAbsent(spec, key -> new ArrayList<>()).add(producer);
    }

    /** Whether anybody registered a way to produce {@code spec} — the gate's cheap question. */
    public static boolean knows(ItemSpec spec) {
        return REGISTERED.containsKey(spec);
    }

    /** Fresh producer methods for {@code spec}, in registration order; empty when nobody knows. */
    public static List<Method> forSpec(ItemSpec spec) {
        List<Supplier<Method>> factories = REGISTERED.get(spec);
        if (factories == null) {
            return List.of();
        }
        List<Method> methods = new ArrayList<>(factories.size());
        for (Supplier<Method> factory : factories) {
            methods.add(factory.get());
        }
        return methods;
    }

    /**
     * Fresh methods from every registration whose spec matches any of {@code ids}, skipping
     * {@code except} (already offered by identity) — how a crafting ingredient no mod declared
     * reaches a producer.
     */
    public static List<Method> forItems(java.util.Set<String> ids, ItemSpec except) {
        // Matched entries sorted by spec NAME, never map order: a saved plan resumes its method
        // BY INDEX, and this map's iteration order is not stable across JVMs (an ItemSpec's hash
        // includes its lambda).
        java.util.TreeMap<String, List<Supplier<Method>>> matched = new java.util.TreeMap<>();
        for (Map.Entry<ItemSpec, List<Supplier<Method>>> entry : REGISTERED.entrySet()) {
            if (entry.getKey() == except) {
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
        for (List<Supplier<Method>> factories : matched.values()) {
            for (Supplier<Method> factory : factories) {
                methods.add(factory.get());
            }
        }
        return methods;
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
