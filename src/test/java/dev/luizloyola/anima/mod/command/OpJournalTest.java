package dev.luizloyola.anima.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link OpJournal} — where a command that changed something gets filed. */
class OpJournalTest {

    private static final AgentId ALICE = AgentId.random();
    private static final AgentId BOB = AgentId.random();
    private static final AgentId CAROL = AgentId.random();
    /** The operator's own id. A player is not an agent and has no journal. */
    private static final AgentId TIOZIN = AgentId.random();

    private final JournalService journal = new JournalService(() -> 5008033L, 64, 12_000L);

    @Test
    void aPartyWideCommandLandsInEveryMembersJournal() {
        // Routing decision, 2026-08-28: a player has no journal, so a command about a party is
        // written to every member — each settler's file then explains on its own why work appeared.
        OpJournal.record(journal, List.of(ALICE, BOB), "TiozinNub",
                "posted #2 gather 2048 spruce_log at (343, 65, 178)");

        for (AgentId who : List.of(ALICE, BOB)) {
            Entry entry = journal.recent(who, 1).get(0);
            assertEquals(Category.OP, entry.category());
            assertEquals("TiozinNub", entry.event());
            assertEquals("posted #2 gather 2048 spruce_log at (343, 65, 178)", entry.detail());
        }
    }

    /** The fan-out is the ones it touched, not everybody the world knows. */
    @Test
    void nobodyOutsideTheCommandsReachHearsAboutIt() {
        OpJournal.record(journal, List.of(ALICE, BOB), "TiozinNub", "cancelled #3");

        assertEquals(1, journal.recent(ALICE, 8).size());
        assertEquals(1, journal.recent(BOB, 8).size());
        assertTrue(journal.recent(CAROL, 8).isEmpty());
    }

    /**
     * {@code journal.op} mutes the whole category, and the helper must go through
     * {@link JournalService#record} for that to reach it — a line written straight to a sink would
     * leave the knob switching nothing.
     */
    @Test
    void theKnobSilencesTheChannelWithoutTheCallersKnowing() {
        OpJournal.record(journal, List.of(ALICE), "TiozinNub", "gave 1 stone_axe");
        journal.mute(Set.of(new JournalService.Muted(Category.OP, null)));
        OpJournal.record(journal, List.of(ALICE), "TiozinNub", "gave 1 stone_pickaxe");

        List<Entry> written = journal.recent(ALICE, 8);
        assertEquals(1, written.size());
        assertEquals("gave 1 stone_axe", written.get(0).detail());
    }

    /**
     * {@code party leave} recorded only to the LEAVER, and an operator running it as themselves
     * is a player — dropped by the filter below, so a settlement-changing command went down
     * nowhere at all. It now records to the party as it stood before the leave, which is also the
     * only way the members left behind learn why their board scope moved.
     */
    @Test
    void aLeaveReachesTheMembersLeftBehindAndNotThePlayerWhoRanIt() {
        List<AgentId> partyBeforeTheLeave = List.of(TIOZIN, BOB, CAROL);

        OpJournal.record(journal, knowing(BOB, CAROL), partyBeforeTheLeave, "TiozinNub",
                "moved TiozinNub out of the party");

        assertEquals("moved TiozinNub out of the party", journal.recent(BOB, 1).get(0).detail());
        assertEquals("moved TiozinNub out of the party", journal.recent(CAROL, 1).get(0).detail());
        assertTrue(journal.recent(TIOZIN, 1).isEmpty());
    }

    /** Nothing but the operator: no ring is minted, and no per-agent file with it. */
    @Test
    void aCommandThatTouchedOnlyAPlayerWritesNothing() {
        OpJournal.record(journal, knowing(BOB), List.of(TIOZIN), "TiozinNub", "left the party");

        assertTrue(journal.recent(TIOZIN, 8).isEmpty());
        assertTrue(journal.recent(BOB, 8).isEmpty());
    }

    /** A directory that knows exactly these agents; anyone else asked about is a player. */
    private static AgentDirectory knowing(AgentId... agents) {
        Map<AgentId, PrivateIdentity> known = new LinkedHashMap<>();
        for (AgentId id : agents) {
            known.put(id, () -> "settler");
        }
        return new AgentDirectory() {
            @Override
            public Optional<PrivateIdentity> identity(AgentId id) {
                return Optional.ofNullable(known.get(id));
            }

            @Override
            public Map<AgentId, PrivateIdentity> known() {
                return known;
            }
        };
    }
}
