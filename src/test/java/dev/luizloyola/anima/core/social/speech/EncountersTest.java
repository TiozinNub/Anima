package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The roster: pins join's find-or-create behavior, close's list move, staleness, retention
 * pruning, and the restore load path against the task brief's semantics.
 */
class EncountersTest {

    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();
    private final AgentId carol = AgentId.random();
    private final AgentId dave = AgentId.random();

    @Test
    @DisplayName("join with no existing encounter creates one, open, holding both parties")
    void joinCreates() {
        Encounters roster = new Encounters();

        Encounter e = roster.join(alice, bob, 100L);

        assertTrue(roster.open().contains(e), "the fresh encounter is seated on the open list");
        assertFalse(roster.closed().contains(e));
        assertTrue(e.includes(alice));
        assertTrue(e.includes(bob));
        assertEquals(100L, e.openedAt());
    }

    @Test
    @DisplayName("join for a pair already open returns the same instance, not a second record")
    void joinIsIdempotent() {
        Encounters roster = new Encounters();

        Encounter first = roster.join(alice, bob, 100L);
        Encounter second = roster.join(alice, bob, 150L);

        assertSame(first, second, "an open encounter for the pair already exists — join must not duplicate it");
        assertEquals(1, roster.open().size());
    }

    @Test
    @DisplayName("openFor finds the one open encounter a body is party to, and none other")
    void openForFindsOwnEncounterOnly() {
        Encounters roster = new Encounters();
        Encounter aliceBob = roster.join(alice, bob, 100L);
        Encounter carolDave = roster.join(carol, dave, 100L);

        assertEquals(Optional.of(aliceBob), roster.openFor(alice));
        assertEquals(Optional.of(aliceBob), roster.openFor(bob));
        assertEquals(Optional.of(carolDave), roster.openFor(carol));
        assertTrue(roster.openFor(AgentId.random()).isEmpty(), "a stranger to every encounter finds none");
    }

    @Test
    @DisplayName("close moves the record to closed, and a further join for the pair opens a new one")
    void closeMovesListsAndFurtherJoinCreatesNew() {
        Encounters roster = new Encounters();
        Encounter first = roster.join(alice, bob, 100L);

        roster.close(first, 200L);

        assertFalse(roster.open().contains(first), "close removes the record from the open list");
        assertTrue(roster.closed().contains(first), "close seats the record on the closed list");
        assertTrue(first.closed());
        assertEquals(200L, first.closedAt());

        Encounter second = roster.join(alice, bob, 250L);

        assertNotSame(first, second, "the pair's prior encounter is closed — join must start a new one");
        assertTrue(roster.open().contains(second));
        assertEquals(1, roster.open().size());
    }

    @Test
    @DisplayName("stale is false exactly at the gap, true past it, and false once the encounter is closed")
    void staleTracksTheGapAndClosedState() {
        Encounters roster = new Encounters();
        Encounter e = roster.join(alice, bob, 100L);

        assertFalse(roster.stale(e, 150L, 50L), "50 ticks of silence is exactly the gap — not yet stale");
        assertTrue(roster.stale(e, 151L, 50L), "51 ticks past opening exceeds a 50-tick gap");

        roster.close(e, 200L);

        assertFalse(roster.stale(e, 500L, 50L), "a closed encounter is never stale");
    }

    @Test
    @DisplayName("prune drops a closed record past retention and keeps a fresher one")
    void pruneDropsOldClosedRecordsAndKeepsFreshOnes() {
        Encounters roster = new Encounters();
        Encounter old = roster.join(alice, bob, 0L);
        roster.close(old, 100L);
        Encounter fresh = roster.join(carol, dave, 0L);
        roster.close(fresh, 190L);

        roster.prune(200L, 50L);

        assertFalse(roster.closed().contains(old), "100 ticks past closing exceeds a 50-tick retention");
        assertTrue(roster.closed().contains(fresh), "10 ticks past closing is well within a 50-tick retention");
    }

    @Test
    @DisplayName("restore seats an open record on the open list and a closed one on the closed list")
    void restoreSeatsOpenAndClosedCorrectly() {
        Encounters roster = new Encounters();
        Encounter openRecord = new Encounter(UUID.randomUUID(), List.of(alice, bob), 50L);
        Encounter closedRecord = new Encounter(UUID.randomUUID(), List.of(carol, dave), 10L);
        closedRecord.close(90L);

        roster.restore(openRecord);
        roster.restore(closedRecord);

        assertTrue(roster.open().contains(openRecord));
        assertFalse(roster.closed().contains(openRecord));
        assertTrue(roster.closed().contains(closedRecord));
        assertFalse(roster.open().contains(closedRecord));
    }
}
