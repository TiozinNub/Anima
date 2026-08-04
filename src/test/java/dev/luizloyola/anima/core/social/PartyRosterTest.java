package dev.luizloyola.anima.core.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The roster's contract: a loner is a party of one minted on first ask, membership is exclusive
 * (join moves, an emptied party vanishes), member order is join order, and leaving only means
 * something when there was company to leave.
 */
class PartyRosterTest {

    private final PartyRoster roster = new PartyRoster();
    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();
    private final AgentId charlie = AgentId.random();

    @Test
    void firstAskMintsAPartyOfOneAndItSticks() {
        PartyId party = roster.partyOf(alice);
        assertEquals(party, roster.partyOf(alice), "the same agent keeps the same party");
        assertEquals(List.of(alice), roster.members(party));
    }

    @Test
    void distinctAgentsGetDistinctParties() {
        assertNotEquals(roster.partyOf(alice), roster.partyOf(bob));
    }

    @Test
    void currentPartyOfNeverCreates() {
        assertTrue(roster.currentPartyOf(alice).isEmpty());
        assertTrue(roster.parties().isEmpty(), "a read must not persist anything");
        PartyId party = roster.partyOf(alice);
        assertEquals(party, roster.currentPartyOf(alice).orElseThrow());
    }

    @Test
    void joinMovesAndDisbandsTheEmptiedParty() {
        PartyId bobsOld = roster.partyOf(bob);
        PartyId alices = roster.partyOf(alice);
        assertTrue(roster.join(bob, alices));
        assertEquals(List.of(alice, bob), roster.members(alices), "member order is join order");
        assertEquals(alices, roster.partyOf(bob), "bob is in alice's party now");
        assertTrue(roster.members(bobsOld).isEmpty(), "his old party disbanded");
        assertFalse(roster.parties().contains(bobsOld));
    }

    @Test
    void joiningYourOwnPartyChangesNothing() {
        PartyId alices = roster.partyOf(alice);
        assertFalse(roster.join(alice, alices));
        assertEquals(List.of(alice), roster.members(alices));
    }

    @Test
    void joinIntoAnUnknownIdCreatesTheParty() {
        // The save-reload path: replaying joins row by row rebuilds every roster verbatim.
        PartyId saved = PartyId.random();
        assertTrue(roster.join(alice, saved));
        assertTrue(roster.join(bob, saved));
        assertEquals(List.of(alice, bob), roster.members(saved));
        assertEquals(saved, roster.partyOf(alice));
    }

    @Test
    void leaveNeedsCompanyToMeanAnything() {
        assertFalse(roster.leave(alice), "no party yet — nothing to leave");
        PartyId alices = roster.partyOf(alice);
        assertFalse(roster.leave(alice), "alone in your own party is already on your own");
        assertEquals(alices, roster.partyOf(alice), "and the party id did not churn");

        roster.join(bob, alices);
        assertTrue(roster.leave(bob));
        assertEquals(List.of(alice), roster.members(alices));
        PartyId bobsFresh = roster.partyOf(bob);
        assertNotEquals(alices, bobsFresh, "the next ask mints him a fresh party of one");
        assertEquals(List.of(bob), roster.members(bobsFresh));
    }

    @Test
    void theLastDeparturesDisbandInOrder() {
        PartyId alices = roster.partyOf(alice);
        roster.join(bob, alices);
        roster.join(charlie, alices);
        assertEquals(3, roster.size(alices));
        assertTrue(roster.leave(alice), "the founder can leave too — no owner slot");
        assertEquals(List.of(bob, charlie), roster.members(alices), "the party outlives its founder");
        assertTrue(roster.leave(bob));
        assertFalse(roster.leave(charlie), "last one standing is a party of one again");
        assertTrue(roster.parties().contains(alices), "and their party persists");
    }

    /**
     * A party of one is the shape almost every agent has and {@code leave} refuses for it, so an
     * eviction routed through {@code leave} strands most rows — the dev world held 722 parties
     * naming members no directory could resolve.
     */
    @Test
    void evictTakesAPartyOfOneWhereLeaveWillNot() {
        PartyId alices = roster.partyOf(alice);
        assertFalse(roster.leave(alice), "leave refuses a loner — that is its contract");
        assertTrue(roster.parties().contains(alices));

        assertTrue(roster.evict(alice), "evict does not");
        assertTrue(roster.currentPartyOf(alice).isEmpty(), "they are in no party at all");
        assertFalse(roster.parties().contains(alices), "and the emptied party is gone with them");
    }

    @Test
    void evictLeavesTheCompanyBehindIntact() {
        PartyId alices = roster.partyOf(alice);
        roster.join(bob, alices);
        roster.join(charlie, alices);

        assertTrue(roster.evict(bob));
        assertEquals(List.of(alice, charlie), roster.members(alices), "the party outlives them");
        assertTrue(roster.currentPartyOf(bob).isEmpty());
    }

    @Test
    void evictingAStrangerChangesNothing() {
        assertFalse(roster.evict(alice), "never in a party, so nothing to take them out of");
        assertTrue(roster.parties().isEmpty(), "and asking must not have minted one");
    }
}
