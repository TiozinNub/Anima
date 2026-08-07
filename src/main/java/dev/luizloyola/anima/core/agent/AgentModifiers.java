package dev.luizloyola.anima.core.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything currently shifting one agent away from its species — the modifier list behind a
 * {@link ModifiedProfile}.
 *
 * <p><b>In memory and derived, never persisted.</b> The consumer persists the <em>source</em> — the
 * lumberjack job, not its modifiers — and re-applies on load: one source of truth, and Anima stores
 * nothing it could not interpret. Re-applying is safe because ids replace rather than stack.
 *
 * <p><b>A version counter, not an event.</b> Resolving three tiers per read would be paid on hot
 * paths (the being sense alone asks several times a tick per body), so {@link ModifiedProfile}
 * folds the answer and re-folds when this number moves.
 *
 * <p>Not thread-safe: applied from the server thread.
 */
public final class AgentModifiers {

    /** An agent with nothing special about it. Immutable, shared, and the overwhelmingly common case. */
    public static final AgentModifiers NONE = new AgentModifiers(true);

    private final Map<ProfileAspect, Map<String, AspectModifier>> byAspect =
            new LinkedHashMap<>();
    private final boolean frozen;
    private long version;

    public AgentModifiers() {
        this(false);
    }

    private AgentModifiers(boolean frozen) {
        this.frozen = frozen;
    }

    /**
     * Applies a modifier, replacing any earlier one with the same id on the same aspect — which is
     * what makes re-applying everything an agent's jobs and traits imply safe at any time, on world
     * load, after a job change, or twice by accident.
     */
    public void apply(AspectModifier modifier) {
        requireMutable();
        byAspect.computeIfAbsent(modifier.aspect(), a -> new LinkedHashMap<>())
                .put(modifier.id(), modifier);
        version++;
    }

    /** Applies several at once — one version bump for the lot. */
    public void applyAll(Iterable<AspectModifier> modifiers) {
        requireMutable();
        for (AspectModifier modifier : modifiers) {
            byAspect.computeIfAbsent(modifier.aspect(), a -> new LinkedHashMap<>())
                    .put(modifier.id(), modifier);
        }
        version++;
    }

    /**
     * Drops every modifier carrying this id, whichever aspects they touched — "this agent is not a
     * lumberjack any more" in one call.
     *
     * @return whether anything was actually removed
     */
    public boolean remove(String id) {
        requireMutable();
        boolean removed = false;
        for (Map<String, AspectModifier> onAspect : byAspect.values()) {
            removed |= onAspect.remove(id) != null;
        }
        if (removed) {
            version++;
        }
        return removed;
    }

    public void clear() {
        requireMutable();
        if (!byAspect.isEmpty()) {
            byAspect.clear();
            version++;
        }
    }

    /** What is shifting this aspect, in the order it was applied. Empty for most aspects. */
    public List<AspectModifier> on(ProfileAspect aspect) {
        Map<String, AspectModifier> onAspect = byAspect.get(aspect);
        return onAspect == null ? List.of() : List.copyOf(onAspect.values());
    }

    public List<AspectModifier> all() {
        List<AspectModifier> all = new ArrayList<>();
        byAspect.values().forEach(onAspect -> all.addAll(onAspect.values()));
        return Collections.unmodifiableList(all);
    }

    /** Whether this agent is, so far, exactly its species. */
    public boolean isEmpty() {
        return byAspect.isEmpty();
    }

    /**
     * Bumped by every change. A resolved read compares it against what it folded from and re-folds
     * when they differ; the value itself means nothing beyond "different from before".
     */
    public long version() {
        return version;
    }

    private void requireMutable() {
        if (frozen) {
            throw new UnsupportedOperationException(
                    "AgentModifiers.NONE is shared by every unmodified agent — give this one its "
                            + "own set before shifting it away from its species");
        }
    }
}
