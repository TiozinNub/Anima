package dev.luizloyola.anima.mod.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Encounters;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link EncounterData#rows} / {@link EncounterData#fromRows}, checkable with no
 * {@link net.minecraft.server.MinecraftServer} — the {@link ContactDataTest} seam pattern applied
 * to a store whose codec has two record levels instead of one.
 */
class EncounterDataTest {

    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();
    private final AgentId carol = AgentId.random();
    private final AgentId dave = AgentId.random();

    @Test
    void openAndClosedEncountersRoundTripThroughRows() {
        EncounterData data = new EncounterData();
        Encounters roster = data.roster();

        Encounter open = roster.join(alice, bob, 10L).orElseThrow();
        open.append(new Utterance(alice, SpeechActs.HAIL.key(), Map.of(), 10L));
        open.append(new Utterance(bob, SpeechActs.GREETING.key(), Map.of("tone", "warm"), 11L));

        // A second pair, not bob again: one open conversation per body, so he cannot be in two.
        Encounter closed = roster.join(carol, dave, 20L).orElseThrow();
        closed.append(new Utterance(carol, SpeechActs.HAIL.key(), Map.of(), 20L));
        roster.close(closed, 30L);

        List<EncounterData.Row> raw = data.rows();
        assertEquals(2, raw.size(), "one open and one closed encounter");

        EncounterData restored = EncounterData.fromRows(1, raw.size(), raw);
        assertEquals(2, restored.actualRows(), "actualRows is open + closed, not either alone");

        Encounters restoredRoster = restored.roster();
        assertEquals(1, restoredRoster.open().size(), "the unclosed encounter seats as open");
        assertEquals(1, restoredRoster.closed().size(), "the closed encounter seats as closed");

        Encounter restoredOpen = restoredRoster.open().get(0);
        assertTrue(restoredOpen.includes(alice));
        assertTrue(restoredOpen.includes(bob));
        assertFalse(restoredOpen.closed());
        List<Utterance> transcript = restoredOpen.transcript();
        assertEquals(2, transcript.size());
        assertEquals(alice, transcript.get(0).author());
        assertEquals(SpeechActs.HAIL.key(), transcript.get(0).act());
        assertEquals(10L, transcript.get(0).tick());
        assertEquals(bob, transcript.get(1).author());
        assertEquals(SpeechActs.GREETING.key(), transcript.get(1).act());
        assertEquals(Map.of("tone", "warm"), transcript.get(1).payload());

        Encounter restoredClosed = restoredRoster.closed().get(0);
        assertTrue(restoredClosed.includes(carol));
        assertTrue(restoredClosed.includes(dave));
        assertTrue(restoredClosed.closed());
        assertEquals(30L, restoredClosed.closedAt());
    }

    @Test
    void aSystemLineRoundTripsToANullAuthorUtterance() {
        EncounterData data = new EncounterData();
        Encounter e = data.roster().join(alice, bob, 5L).orElseThrow();
        e.append(Utterance.system(SpeechActs.STALE.key(), bob, 6L));

        EncounterData restored = EncounterData.fromRows(1, 1, data.rows());

        Utterance u = restored.roster().open().get(0).transcript().get(0);
        assertNull(u.author(), "an empty author round-trips to the SYSTEM speaker, not a real one");
        assertTrue(u.system());
        assertEquals(SpeechActs.STALE.key(), u.act());
    }
}
