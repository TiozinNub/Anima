package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p><b>A name can be known before its face</b> (group-up spec, decision 18): joining a party
 * teaches the names of members who were not there, and {@link #hear} keeps those apart from
 * {@link #knows}, which is what a nameplate and a percept's name read. Learning the face moves the
 * entry across.
 *
 * <p>Pure core and single-threaded by contract (the server thread); persistence is the
 * {@code mod} layer's job.
 */
public final class ContactBook {

    /** Insertion-ordered per knower, so a save round-trips in a stable, readable order. */
    private final Map<AgentId, Set<AgentId>> books = new HashMap<>();
    /** Names told without a face; never holds anyone the same knower {@link #knows}. */
    private final Map<AgentId, Set<AgentId>> heard = new HashMap<>();

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
        boolean fresh = books.computeIfAbsent(knower, key -> new LinkedHashSet<>()).add(whom);
        if (fresh) {
            drop(heard, knower, whom);
        }
        return fresh;
    }

    /**
     * {@code knower} is told {@code whom}'s name without seeing who it belongs to. {@code true} when
     * this is news: somebody already known, or already heard of, changes nothing.
     */
    public boolean hear(AgentId knower, AgentId whom) {
        if (knows(knower, whom)) {
            return false;
        }
        return heard.computeIfAbsent(knower, key -> new LinkedHashSet<>()).add(whom);
    }

    /** Whether {@code knower} has a name for {@code whom} but not yet a face. */
    public boolean heardOf(AgentId knower, AgentId whom) {
        Set<AgentId> names = heard.get(knower);
        return names != null && names.contains(whom);
    }

    /** Everyone {@code knower} has only heard of, in the order they were told. */
    public Set<AgentId> heardBy(AgentId knower) {
        Set<AgentId> names = heard.get(knower);
        return names == null ? Set.of() : Collections.unmodifiableSet(names);
    }

    /** Everyone who has heard of anyone — for saving. */
    public Set<AgentId> hearers() {
        return Collections.unmodifiableSet(heard.keySet());
    }

    private static boolean drop(Map<AgentId, Set<AgentId>> map, AgentId knower, AgentId whom) {
        Set<AgentId> set = map.get(knower);
        if (set == null || !set.remove(whom)) {
            return false;
        }
        if (set.isEmpty()) {
            map.remove(knower);
        }
        return true;
    }

    /** Both sides learn each other. */
    public boolean introduce(AgentId one, AgentId other) {
        boolean learned = learn(one, other);
        return learn(other, one) || learned;
    }

    /** Returns whether anything was actually dropped — a name only heard counts. */
    public boolean forget(AgentId knower, AgentId whom) {
        boolean known = drop(books, knower, whom); // an empty book is indistinguishable from no book
        return drop(heard, knower, whom) || known;
    }

    /** Returns whether they knew, or had heard of, anyone at all. */
    public boolean clear(AgentId knower) {
        boolean known = books.remove(knower) != null;
        return heard.remove(knower) != null || known;
    }

    /**
     * Takes {@code who} out of the books in both directions — their own, and the entry naming them
     * in everybody else's. For an agent being unmade, who should leave no trace that they existed.
     *
     * <p><b>A burial must not call this:</b> "I knew Alice" stays true after Alice dies, so the
     * dead are filtered out of a listing at read time instead.
     *
     * @return whether they appeared anywhere
     */
    public boolean erase(AgentId who) {
        boolean any = clear(who);
        for (AgentId knower : List.copyOf(books.keySet())) {
            any |= forget(knower, who);
        }
        for (AgentId knower : List.copyOf(heard.keySet())) {
            any |= forget(knower, who);
        }
        return any;
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
