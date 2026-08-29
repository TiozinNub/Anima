package dev.luizloyola.anima.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.log.JournalService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link OpJournal} — where a command that changed something gets filed. */
class OpJournalTest {

    private static final AgentId ALICE = AgentId.random();
    private static final AgentId BOB = AgentId.random();
    private static final AgentId CAROL = AgentId.random();

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
}
