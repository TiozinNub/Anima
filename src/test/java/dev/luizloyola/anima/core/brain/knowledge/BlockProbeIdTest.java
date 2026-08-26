package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link BlockProbe#idAt} — the exact-species question, separate from the coarse
 * {@link BlockProbe#at} every rule already relies on.
 */
class BlockProbeIdTest {

    @Test
    void theProbeCanBeAskedExactlyWhatABlockIs() {
        FakeProbe probe = new FakeProbe();
        probe.set(4, 64, 4, BlockKind.LOG);
        probe.setId(4, 64, 4, "minecraft:birch_log");

        assertEquals("minecraft:birch_log", probe.idAt(4, 64, 4));
        assertEquals(BlockKind.LOG, probe.at(4, 64, 4),
                "the coarse question is unchanged — every log is still a LOG");
        assertEquals("", probe.idAt(9, 64, 9), "nothing there, nothing to name");
    }

    @Test
    void anUnloadedColumnIsOutOfReachForBothQuestions() {
        FakeProbe probe = new FakeProbe();
        probe.setId(4, 64, 4, "minecraft:birch_log");
        probe.set(4, 64, 4, BlockKind.LOG);
        probe.markUnloaded(4, 4);

        assertEquals("", probe.idAt(4, 64, 4),
                "the column is unloaded — an id set before that must not leak through");
        assertEquals(BlockKind.UNKNOWN, probe.at(4, 64, 4),
                "the two accessors must agree about reachability");
    }
}
