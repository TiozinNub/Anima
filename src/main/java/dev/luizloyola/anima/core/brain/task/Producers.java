package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemCall;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.Kit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

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

    /** One way to produce, the items it can ever make, and the tools its work uses. */
    private record Registration(Predicate<String> yields, Kit tools, Factory factory) {
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
        register(spec, yields, Kit.NONE, producer);
    }

    /**
     * The same, for a way whose work uses tools: felling wants an axe. An {@link ObtainItem}
     * running this way gets a missing tool before each round, so one that wears out mid-errand is
     * replaced at the next tree rather than the rest cut bare-handed.
     */
    public static void register(ItemSpec spec, Predicate<String> yields, Kit tools, Factory producer) {
        REGISTERED.computeIfAbsent(spec, key -> new ArrayList<>())
                .add(new Registration(id -> spec.matches(id) && yields.test(id), tools, producer));
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
        return forSpec(spec, null);
    }

    /**
     * The same, each behind its tools for a goal already obtaining {@code pursued}; {@code null}
     * for a caller that only asks whether a way applies.
     */
    public static List<Method> forSpec(ItemSpec spec, java.util.@Nullable Set<String> pursued) {
        List<Registration> ways = REGISTERED.get(spec);
        if (ways == null) {
            return List.of();
        }
        List<Method> methods = new ArrayList<>(ways.size());
        for (Registration way : ways) {
            methods.add(tooled(way, way.factory().create(spec), pursued));
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
        return forItems(ids, wanted, null);
    }

    /** {@link #forItems(java.util.Set, ItemSpec)}, each behind its tools as {@link #forSpec} puts them. */
    public static List<Method> forItems(java.util.Set<String> ids, ItemSpec wanted,
                                        java.util.@Nullable Set<String> pursued) {
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
                methods.add(tooled(way, way.factory().create(wanted), pursued));
            }
        }
        return methods;
    }

    private static Method tooled(Registration way, Method method, java.util.@Nullable Set<String> pursued) {
        return pursued == null || way.tools().isEmpty() ? method : new Tooled(method, way.tools(), pursued);
    }

    /**
     * A way behind the tools its work uses, asked for on every decompose — once a round, so per
     * tree for a chop. A tool an ancestor is already obtaining is skipped: an axe made of planks
     * made of logs reaches this chop again, and asking for the axe there would never end.
     */
    private record Tooled(Method way, Kit tools, java.util.Set<String> pursued) implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return way.applicable(ctx);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return way.estimateCost(ctx);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Task> plan = new ArrayList<>();
            for (ItemCall call : tools.calls()) {
                if (call.coveredBy(ctx.percepts().inventory())
                        || pursued.stream().anyMatch(call.spec()::matches)) {
                    continue;
                }
                ObtainItem obtain = new ObtainItem(call.spec(), call.count(), pursued);
                plan.add(call.strength() == ItemCall.Strength.NEED ? obtain : new Try(obtain));
            }
            plan.addAll(way.decompose(ctx));
            return plan;
        }

        @Override
        public String describe() {
            return way.describe();
        }
    }

    /** Forgets every registration — test teardown only. */
    public static void reset() {
        REGISTERED.clear();
    }
}
