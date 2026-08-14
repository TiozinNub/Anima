package dev.luizloyola.anima.core.inv;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A named CLASS of items — what a goal or a work item means by "logs": not one id but a family,
 * matched by predicate over the core inventory's id strings. One spec object serves everyone who
 * must agree on the meaning (the board's stock predicate, an {@code ObtainItem}, the drop filters),
 * so there is one matcher and no drift.
 *
 * <p>Anima declares no constants of its own: Which items matter belongs to the consuming mod,
 * which declares its specs and registers how to produce them with {@code Producers}.
 */
public record ItemSpec(String name, Predicate<String> matcher) {

    /** Canonical instances by name — see {@link #register}. */
    private static final Map<String, ItemSpec> REGISTERED = new ConcurrentHashMap<>();

    /**
     * Declares a class of items so a plan holding one can be written down.
     *
     * <p>A spec's meaning is a lambda and a lambda cannot be saved; the NAME can, so anything a
     * task might carry has to be registered — on load the name is looked up and the one canonical
     * instance comes back, matcher and all. Same shape as {@code PoiKind} and {@code BlockKind}.
     *
     * <p>Registering the same name twice returns the first instance rather than replacing it:
     * swapping the matcher under a task already holding one would change what it was looking for
     * mid-errand.
     */
    public static ItemSpec register(ItemSpec spec) {
        return REGISTERED.computeIfAbsent(spec.name(), ignored -> spec);
    }

    /** The canonical spec for {@code name}, if a mod declared one. */
    public static Optional<ItemSpec> byName(String name) {
        return Optional.ofNullable(REGISTERED.get(name));
    }

    /** Every registered name, for tab completion and readouts. Snapshot, not a live view. */
    public static java.util.Set<String> names() {
        return java.util.Set.copyOf(REGISTERED.keySet());
    }

    /** The literal id-sets behind {@link #anyOf} specs, so a codec can write one down by content. */
    private static final Map<String, java.util.Set<String>> LITERALS = new ConcurrentHashMap<>();

    /**
     * The spec meaning "any one of exactly these items" — the shape a crafting ingredient has
     * ("any plank"), built from content rather than declared by a mod. A plan mid-flight persists
     * its specs BY NAME, so the name is deterministic in the sorted ids (readable head, hash
     * tail), {@link #literalIds} hands a codec the content, and loading re-canonicalises through
     * here.
     */
    public static ItemSpec anyOf(java.util.Set<String> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("a spec matches at least one item");
        }
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(ids);
        String name = literalName(sorted);
        ItemSpec spec = register(new ItemSpec(name, sorted::contains));
        LITERALS.putIfAbsent(name, java.util.Set.copyOf(sorted));
        return spec;
    }

    /** The exact ids behind a literal spec, or empty for a mod-declared one — the codec's fork. */
    public static Optional<java.util.Set<String>> literalIds(ItemSpec spec) {
        return Optional.ofNullable(LITERALS.get(spec.name()));
    }

    /**
     * Readable head, collision-proof tail: {@code oak_log} alone for a singleton, else
     * {@code oak_planks+11#1a2b3c4d}. The hash is over the full sorted join and
     * {@code String.hashCode} is specified, so the name is stable across JVMs and saves and two
     * plank-families cannot steal each other's name.
     */
    private static String literalName(java.util.TreeSet<String> sorted) {
        String first = sorted.first();
        String head = first.startsWith("minecraft:") ? first.substring("minecraft:".length()) : first;
        if (sorted.size() == 1) {
            return head;
        }
        String joined = String.join("|", sorted);
        return head + "+" + (sorted.size() - 1) + "#" + Integer.toHexString(joined.hashCode());
    }

    public boolean matches(String itemId) {
        return matcher.test(itemId);
    }
}
