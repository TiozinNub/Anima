package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every agent's {@link AgentKnowledge}, keyed by {@link AgentId}: memories belong to the
 * <em>agent</em>, not the entity, so they survive chunk unloads and the entity-free layers read
 * them offline. Pure and world-agnostic — the consuming mod wraps it in a world-scoped SavedData,
 * rebuilt on load by replaying {@code note()}.
 */
public final class KnowledgeRegistry {
    private final Map<AgentId, AgentKnowledge> byPerson = new LinkedHashMap<>();

    /** This person's knowledge, created empty on first ask — never null, always the same object. */
    public AgentKnowledge forPerson(AgentId id) {
        Objects.requireNonNull(id, "id");
        return byPerson.computeIfAbsent(id, k -> new AgentKnowledge());
    }

    /** Every person who has (or ever asked for) knowledge, insertion-ordered — the codec's view. */
    public Set<AgentId> persons() {
        return Collections.unmodifiableSet(byPerson.keySet());
    }

    /**
     * Drops a person's knowledge outright. <b>No caller today</b>; kept as the wipe a burial
     * performs — only its own agent ever reads a mind's map, and it is the largest per-agent store
     * by an order of magnitude. See
     * {@code docs/superpowers/specs/2026-08-03-persistence-design.md}.
     */
    public boolean remove(AgentId id) {
        return byPerson.remove(id) != null;
    }
}
