package dev.luizloyola.anima.core.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who belongs to which party — every roster in the world, keyed by {@link PartyId}.
 *
 * <p><b>A loner is a party of one.</b> There is no "not in a party" state: {@link #partyOf}
 * answers for <em>any</em> agent, minting a fresh party on first ask, so layer 3 always has a
 * party to scope a board to.
 *
 * <p><b>A party is an array of members with no owner</b> (decision: Luiz, social foundations §6).
 * Member order is join order; there is no leader slot. Players are ordinary members — their
 * {@link AgentId} is minted from the account UUID — though layer 3 never assigns a player work
 * items.
 *
 * <p><b>Membership is exclusive.</b> {@link #join} moves rather than adds, and a party emptied by
 * its last member's departure vanishes. Whole-party union (merge-on-handshake) arrives with the
 * social rung, not here.
 *
 * <p>Pure core and single-threaded by contract (the server thread); persistence is the
 * {@code mod} layer's job.
 */
public final class PartyRoster {

    /** Members in join order per party; the list is never empty while the key exists. */
    private final Map<PartyId, List<AgentId>> parties = new HashMap<>();
    private final Map<AgentId, PartyId> byMember = new HashMap<>();

    /**
     * The party {@code member} belongs to, minting a party of one on first ask. The caller that
     * persists this store should check {@link #currentPartyOf} first to know whether anything
     * changed.
     */
    public PartyId partyOf(AgentId member) {
        PartyId existing = byMember.get(member);
        if (existing != null) {
            return existing;
        }
        PartyId fresh = PartyId.random();
        parties.put(fresh, new ArrayList<>(List.of(member)));
        byMember.put(member, fresh);
        return fresh;
    }

    /** As {@link #partyOf}, but never creates — empty means "no one has ever asked". */
    public Optional<PartyId> currentPartyOf(AgentId member) {
        return Optional.ofNullable(byMember.get(member));
    }

    /**
     * Moves {@code who} into {@code into}, leaving (and, if emptied, disbanding) their current
     * party. Returns {@code true} when membership actually changed. An unknown target id becomes a
     * real party by the move — also how a save reloads: replaying joins row by row rebuilds every
     * roster.
     */
    public boolean join(AgentId who, PartyId into) {
        if (into.equals(byMember.get(who))) {
            return false;
        }
        remove(who);
        parties.computeIfAbsent(into, key -> new ArrayList<>()).add(who);
        byMember.put(who, into);
        return true;
    }

    /**
     * {@code who} strikes out on their own: they leave their party and their next
     * {@link #partyOf} mints a fresh party of one. Returns {@code false} for an agent already
     * alone in their own party — re-minting their id would churn every reference for no
     * observable change.
     */
    public boolean leave(AgentId who) {
        PartyId current = byMember.get(who);
        if (current == null || parties.get(current).size() == 1) {
            return false;
        }
        remove(who);
        return true;
    }

    /** The members of {@code party} in join order — empty if no such party (any more). */
    public List<AgentId> members(PartyId party) {
        List<AgentId> members = parties.get(party);
        return members == null ? List.of() : Collections.unmodifiableList(members);
    }

    /** Every party that exists — for saving and for dev listings. */
    public Set<PartyId> parties() {
        return Collections.unmodifiableSet(parties.keySet());
    }

    public int size(PartyId party) {
        return members(party).size();
    }

    private void remove(AgentId who) {
        PartyId current = byMember.remove(who);
        if (current == null) {
            return;
        }
        List<AgentId> members = parties.get(current);
        members.remove(who);
        if (members.isEmpty()) {
            parties.remove(current); // an empty party is indistinguishable from no party
        }
    }
}
