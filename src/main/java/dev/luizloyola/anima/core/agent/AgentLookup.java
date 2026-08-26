package dev.luizloyola.anima.core.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turning a typed token into one agent.
 *
 * <p>Pure, and here rather than beside the commands, because the rule is worth a test and a
 * {@code CommandSourceStack} is not something a test can hold. The command layer decides where the
 * roster comes from — every loaded body, or the whole directory — and what to print for each
 * outcome.
 */
public final class AgentLookup {

    private AgentLookup() {
    }

    /** What a token resolved to. */
    public sealed interface Result permits Found, None, Ambiguous {
    }

    public record Found(AgentId id) implements Result {
    }

    public record None() implements Result {
    }

    /** More than one candidate, and never a guess between them — see {@link #match}. */
    public record Ambiguous(List<AgentId> candidates) implements Result {
    }

    /**
     * The one agent {@code token} names among {@code candidates}, by id prefix first and then by
     * name.
     *
     * <p><b>Id before name, and ambiguity before either.</b> An id is the unambiguous handle, so it
     * wins — otherwise an agent NAMED like another's short id would shadow the agent that id
     * belongs to. Names are not unique, so two matches is a failure rather than a nearest-wins
     * guess (decision: Luiz): a name collides across kinds the moment several mods share a world,
     * and picking the closer of a settler and a wolf is a worse answer than asking.
     */
    public static Result match(Map<AgentId, String> candidates, String token) {
        String trimmed = token.trim();
        // Every id starts with the empty string, so an empty token would prefix-match the whole
        // roster and come back as "which of these did you mean" rather than as nothing typed.
        if (trimmed.isEmpty()) return new None();

        String lower = trimmed.toLowerCase(Locale.ROOT);
        List<AgentId> byId = candidates.keySet().stream()
                .filter(id -> id.toString().toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
        List<AgentId> matches = byId.isEmpty()
                ? candidates.entrySet().stream()
                        .filter(entry -> entry.getValue().equalsIgnoreCase(trimmed))
                        .map(Map.Entry::getKey)
                        .toList()
                : byId;

        if (matches.isEmpty()) return new None();
        if (matches.size() > 1) return new Ambiguous(matches);
        return new Found(matches.get(0));
    }
}
