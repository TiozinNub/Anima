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

    public boolean matches(String itemId) {
        return matcher.test(itemId);
    }
}
