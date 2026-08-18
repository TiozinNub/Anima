package dev.luizloyola.anima.mod.webdebug;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the browser is currently looking at — the dashboard's answer to {@code DebugLayer}'s mask,
 * one level up.
 *
 * <p><b>The browser declares its interest; the server publishes exactly that.</b> Detail for every
 * agent on every frame would be most of a settlement's mind twenty times a second, and a per-click
 * request would have to read the world from an HTTP thread, which is the one thing
 * {@link WebFeed} exists to prevent. So expanding a card POSTs the id here and the next tick-side
 * build includes that agent's sections and nobody else's.
 *
 * <p>One watch per server, not one per connection: this is a single-operator debug tool, and a
 * second browser showing a different expansion is not worth a session table.
 *
 * <p>Immutable and swapped whole, for the reason everything else here is.
 */
record WebWatch(Set<AgentId> expanded, @Nullable UUID actingAs) {

    /** Nothing expanded and nobody to act as — what a fresh server and a closed browser both mean. */
    static final WebWatch NONE = new WebWatch(Set.of(), null);

    WebWatch {
        expanded = Set.copyOf(expanded);
    }

    boolean isExpanded(AgentId id) {
        return expanded.contains(id);
    }

    /**
     * This watch with {@code id} expanded or collapsed — the click.
     *
     * <p>Insertion-ordered so the set reads the way the operator built it when it is logged.
     */
    WebWatch toggled(AgentId id, boolean open) {
        Set<AgentId> next = new LinkedHashSet<>(expanded);
        if (open) {
            next.add(id);
        } else {
            next.remove(id);
        }
        return new WebWatch(next, actingAs);
    }

    /** This watch driving commands as {@code player} — the per-player half of pin, layers and glow. */
    WebWatch actingAs(@Nullable UUID player) {
        return new WebWatch(expanded, player);
    }
}
