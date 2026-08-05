package dev.luizloyola.anima.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The one property that matters: a stream written down and put back is the same stream. */
class AgentRandomTest {

    @Test
    void aSavedStreamResumesExactly() {
        AgentRandom live = new AgentRandom(12345L);
        for (int i = 0; i < 50; i++) {
            live.nextLong();
        }
        long saved = live.state();

        long[] wouldHaveBeen = new long[20];
        for (int i = 0; i < wouldHaveBeen.length; i++) {
            wouldHaveBeen[i] = live.nextLong();
        }

        AgentRandom reloaded = new AgentRandom(0L);
        reloaded.restore(saved);
        for (long expected : wouldHaveBeen) {
            assertEquals(expected, reloaded.nextLong(),
                    "the draw after a reload must be the draw that was coming");
        }
    }

    @Test
    void theDerivedMethodsResumeToo() {
        // The instincts do not call nextLong: flee jitters with nextInt(bound) and roaming draws
        // doubles. RandomGenerator derives both from nextLong, so restoring the state restores them.
        AgentRandom live = new AgentRandom(999L);
        live.nextInt(7);
        long saved = live.state();
        int nextInt = live.nextInt(7);
        double nextDouble = live.nextDouble();

        AgentRandom reloaded = new AgentRandom(0L);
        reloaded.restore(saved);
        assertEquals(nextInt, reloaded.nextInt(7));
        assertEquals(nextDouble, reloaded.nextDouble());
    }

    @Test
    void twoAgentsSeededDifferentlyDoNotWalkTogether() {
        AgentRandom one = new AgentRandom(1L);
        AgentRandom two = new AgentRandom(2L);
        assertNotEquals(one.nextLong(), two.nextLong());
    }

    @Test
    void everySeedIsALegalState() {
        // SplitMix64 has no bad seeds, so it was chosen: a hand-edited or corrupted save
        // cannot produce a generator that repeats or sticks. Zero is the one people try.
        AgentRandom zero = new AgentRandom(0L);
        Set<Long> draws = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            draws.add(zero.nextLong());
        }
        assertTrue(draws.size() > 990, "a degenerate stream would collide constantly");
    }

    @Test
    void theStateIsTheWholeOfIt() {
        // Two generators at the same state are the same generator, however they got there — the
        // property that lets a single long in NBT stand for the stream.
        AgentRandom walked = new AgentRandom(42L);
        for (int i = 0; i < 17; i++) {
            walked.nextLong();
        }
        AgentRandom jumped = new AgentRandom(walked.state());
        assertEquals(walked.nextLong(), jumped.nextLong());
    }
}
