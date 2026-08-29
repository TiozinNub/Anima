package dev.luizloyola.anima.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.social.PartyId;
import dev.luizloyola.anima.mod.social.PartyData;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@code party leave} records, and to whom — {@link AgentCommands#partyBeforeLeaving} against
 * a real {@link PartyData}, which needs no server.
 *
 * <p>The handler itself needs a live {@code CommandSourceStack}; everything about it that can go
 * wrong quietly is in the capture, and that is here.
 */
class PartyLeaveJournalTest {

    private static final AgentId ALICE = AgentId.random();
    private static final AgentId BOB = AgentId.random();
    private static final AgentId CAROL = AgentId.random();

    private final JournalService journal = new JournalService(() -> 5010100L, 64, 12_000L);
    private final PartyData parties = new PartyData();

    /** Alice, Bob and Carol in one party, in join order. */
    private PartyId together() {
        PartyId party = parties.partyOf(ALICE);
        parties.join(BOB, party);
        parties.join(CAROL, party);
        return party;
    }

    /**
     * {@code PartyRoster.members} hands back an unmodifiable <em>view</em> over its own backing
     * list, and {@code leave} removes the leaver from that very list. A capture that did not copy
     * would read back POST-leave, and Bob — whose departure this is — would drop out of the set
     * between the capture and the record.
     */
    @Test
    void theCaptureSurvivesTheLeaveThatMutatesTheRoster() {
        together();

        List<AgentId> was = AgentCommands.partyBeforeLeaving(parties, BOB);
        assertTrue(parties.leave(BOB));

        assertEquals(List.of(ALICE, BOB, CAROL), was);
    }

    /** The line reaches the whole party, the leaver included — the mirror of {@code party join}. */
    @Test
    void aLeaveIsRecordedToEverybodyIncludingTheOneWhoLeft() {
        together();

        List<AgentId> was = AgentCommands.partyBeforeLeaving(parties, BOB);
        parties.leave(BOB);
        OpJournal.record(journal, was, "TiozinNub", "moved Bob out of the party");

        for (AgentId who : List.of(ALICE, BOB, CAROL)) {
            assertEquals("moved Bob out of the party", journal.recent(who, 1).get(0).detail());
        }
    }

    /**
     * A loner has no party to tell. Through {@code currentPartyOf} rather than {@code partyOf},
     * which would MINT one and dirty the save just to build a log line — so the capture must also
     * leave the store clean.
     */
    @Test
    void aLonerCapturesNobodyAndMintsNothing() {
        assertEquals(List.of(), AgentCommands.partyBeforeLeaving(parties, ALICE));

        assertTrue(parties.currentPartyOf(ALICE).isEmpty());
        assertFalse(parties.isDirty());
    }
}
