package dev.luizloyola.anima.mod.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link JournalFileSink#render} and the wall-clock stamp it is built from. */
class JournalFileSinkTest {

    private static final AgentId ALICE = AgentId.random();

    /** The stamp is taken when the entry ARRIVES, not when the batch flushes seconds later. */
    @Test
    void aLineCarriesTheWallClockOfTheMomentItWasRecorded() {
        String line = JournalFileSink.render(
                new Entry(5008078L, Category.SENSE, "peer", "a stranger now idle"),
                LocalDateTime.of(2026, 8, 28, 17, 22, 19));
        assertEquals("17:22:19 [5008078] sense - peer - a stranger now idle", line);
    }

    @Test
    void anEmptyDetailLeavesNoTrailingSeparator() {
        String line = JournalFileSink.render(
                new Entry(12L, Category.BRAIN, "wander", ""),
                LocalDateTime.of(2026, 8, 28, 9, 5, 1));
        assertEquals("09:05:01 [12] brain - wander", line);
    }

    /**
     * The property that actually matters, and the one a render test cannot reach: two entries
     * arriving seconds apart must keep their own stamps through a single batched flush. Stamping in
     * flush() instead would smear a whole batch to the same late time and quietly make every
     * timestamp a lie. Drives the real onEntry -> queue -> flush -> render -> disk path (via
     * close(), which forces a final flush) rather than a second, parallel implementation of it.
     */
    @Test
    void twoEntriesInOneBatchKeepTheirOwnStamps(@TempDir Path dir) throws IOException {
        List<LocalDateTime> ticks = new ArrayList<>(List.of(
                LocalDateTime.of(2026, 8, 28, 17, 22, 19),
                LocalDateTime.of(2026, 8, 28, 17, 22, 25)));
        JournalFileSink sink = JournalFileSink.sinkFor(dir, () -> ticks.remove(0));

        sink.onEntry(ALICE, new Entry(1L, Category.BODY, "hurt", ""));
        sink.onEntry(ALICE, new Entry(2L, Category.BODY, "died", "sweet berry bush"));
        sink.close(); // stops the writer thread and forces the real flush() before returning

        List<String> written = Files.readAllLines(dir.resolve("agent-" + ALICE.value() + "-agent.log"));
        assertEquals("17:22:19 [1] body - hurt", written.get(1)); // written.get(0) is the file header
        assertEquals("17:22:25 [2] body - died - sweet berry bush", written.get(2));
    }
}
