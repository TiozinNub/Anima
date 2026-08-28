package dev.luizloyola.anima.core.social.speech;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The encounter is a passive record — nothing here ticks. These tests pin the transcript
 * doctrine from the social foundations spec §5: append-only, SYSTEM lines idempotent per
 * subject, the last line is why the conversation ended.
 */
class EncounterTest {

    private final AgentId alice = AgentId.random();
    private final AgentId bob = AgentId.random();

    private Encounter fresh() {
        return new Encounter(UUID.randomUUID(), List.of(alice, bob), 100L);
    }

    @Test
    @DisplayName("a fresh encounter is open, empty, and knows its participants")
    void freshEncounter() {
        Encounter e = fresh();
        assertFalse(e.closed());
        assertTrue(e.transcript().isEmpty());
        assertTrue(e.includes(alice));
        assertEquals(bob, e.other(alice).orElseThrow());
        assertEquals(100L, e.lastActivityTick(), "no lines yet — activity is the opening");
    }

    @Test
    @DisplayName("append keeps order and moves the activity clock")
    void appendOrders() {
        Encounter e = fresh();
        e.append(new Utterance(alice, "hail", Map.of(), 110L));
        e.append(new Utterance(bob, "greeting", Map.of(), 130L));
        assertEquals(List.of("hail", "greeting"),
                e.transcript().stream().map(Utterance::act).toList());
        assertEquals(130L, e.lastActivityTick());
        assertEquals(bob, e.last().orElseThrow().author());
    }

    @Test
    @DisplayName("a SYSTEM line is authorless and idempotent per subject")
    void systemIdempotence() {
        Encounter e = fresh();
        Utterance ignored = new Utterance(null, "ignored",
                Map.of("subject", bob.toString()), 200L);
        e.append(ignored);
        assertTrue(e.hasSystem("ignored", bob), "both parties noticing must not write twice");
        assertFalse(e.hasSystem("ignored", alice));
    }

    @Test
    @DisplayName("a closed encounter refuses new lines — the record is finished")
    void closedRefuses() {
        Encounter e = fresh();
        e.close(300L);
        assertTrue(e.closed());
        assertEquals(300L, e.closedAt());
        assertThrows(IllegalStateException.class,
                () -> e.append(new Utterance(alice, "greeting", Map.of(), 310L)));
    }
}
