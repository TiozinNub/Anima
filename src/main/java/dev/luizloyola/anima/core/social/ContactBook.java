package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Who knows whom — the world's contact books, one per knower, keyed by {@link AgentId}.
 *
 * <p><b>A name is not a property of a body</b> but something you were told (decision: Luiz).
 * Perception says what a body is doing and looks like; this says whether the observer may put a
 * name to it. External identity (skin, gender, model) is public and syncs to everyone; the name
 * is earned.
 *
 * <p><b>Players are ordinary knowers</b> — a player's {@link AgentId} is minted from their
 * account UUID, so their book is another row in this store rather than a parallel system.
 *
 * <p><b>Knowing is one-directional.</b> {@link #learn} records what one side now knows;
 * {@link #introduce} writes two independent facts at the same moment.
 *
 * <p>Pure core and single-threaded by contract (the server thread); persistence is the
 * {@code mod} layer's job.
 */
public final class ContactBook {

    /** Insertion-ordered per knower, so a save round-trips in a stable, readable order. */
    private final Map<AgentId, Set<AgentId>> books = new HashMap<>();

    /**
     * Whether {@code knower} can put a name to {@code whom}. Everyone knows themselves, so a
     * Person's own journal and commands never have to special-case it.
     */
    public boolean knows(AgentId knower, AgentId whom) {
        if (knower.equals(whom)) {
            return true;
        }
        Set<AgentId> book = books.get(knower);
        return book != null && book.contains(whom);
    }

    /**
     * Returns {@code true} when this was genuinely new — the caller's cue to sync a client or
     * narrate the moment; a repeat introduction is a no-op.
     */
    public boolean learn(AgentId knower, AgentId whom) {
        if (knower.equals(whom)) {
            return false; // already true by construction, and never worth storing
        }
        return books.computeIfAbsent(knower, key -> new LinkedHashSet<>()).add(whom);
    }

    /** Both sides learn each other. */
    public boolean introduce(AgentId one, AgentId other) {
        boolean learned = learn(one, other);
        return learn(other, one) || learned;
    }

    /** Returns whether anything was actually dropped. */
    public boolean forget(AgentId knower, AgentId whom) {
        Set<AgentId> book = books.get(knower);
        if (book == null || !book.remove(whom)) {
            return false;
        }
        if (book.isEmpty()) {
            books.remove(knower); // an empty book is indistinguishable from no book
        }
        return true;
    }

    /** Returns whether they knew anyone at all. */
    public boolean clear(AgentId knower) {
        return books.remove(knower) != null;
    }

    /** Everyone {@code knower} can name, in the order they were met. Never includes themselves. */
    public Set<AgentId> contactsOf(AgentId knower) {
        Set<AgentId> book = books.get(knower);
        return book == null ? Set.of() : Collections.unmodifiableSet(book);
    }

    /** Everyone who knows anyone — for saving and for dev listings. */
    public Set<AgentId> knowers() {
        return Collections.unmodifiableSet(books.keySet());
    }

    public int size(AgentId knower) {
        return contactsOf(knower).size();
    }
}
