package dev.luizloyola.anima.mod.body;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.compat.ChunkTickets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentTicketsTest {

    @Test
    @DisplayName("a ticket outlives three beats, so one late renewal never unloads a body's chunks")
    void timeoutCoversThreeBeats() {
        assertTrue(ChunkTickets.TIMEOUT >= 3L * AgentTickets.BEAT,
                "timeout " + ChunkTickets.TIMEOUT + " against beat " + AgentTickets.BEAT);
    }
}
