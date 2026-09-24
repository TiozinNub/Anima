package dev.luizloyola.anima.core.brain.gate;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The open registry of {@link Act}s — {@code Doings}' pattern. Empty until something performs one:
 * an act is declared beside the machinery that does it, never ahead of it.
 */
public final class Acts {

    private static final Map<String, Act> REGISTRY = new LinkedHashMap<>();

    private Acts() {
    }

    public static synchronized Act register(Act act) {
        Act prior = REGISTRY.putIfAbsent(act.key(), act);
        if (prior != null) {
            throw new IllegalStateException("act '" + act.key() + "' is already declared");
        }
        return act;
    }

    public static synchronized Optional<Act> byKey(String key) {
        return Optional.ofNullable(REGISTRY.get(key));
    }

    public static synchronized Collection<Act> all() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }
}
