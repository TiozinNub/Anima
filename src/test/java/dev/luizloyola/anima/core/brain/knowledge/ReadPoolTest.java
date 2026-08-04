package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ceiling the place sense never had: hard, fair regardless of tick order, banking quiet
 * ticks for the wood ahead, and off when it is switched off.
 */
class ReadPoolTest {

    private static ReadPool pool(int ceiling, int population) {
        ReadPool pool = new ReadPool(() -> ceiling);
        pool.population(population);
        return pool;
    }

    @Test
    @DisplayName("nobody is granted past the ceiling, however many ask")
    void theCeilingIsHard() {
        ReadPool pool = pool(100, 10);
        int granted = 0;
        for (int agent = 0; agent < 50; agent++) {
            granted += pool.grant("agent" + agent, 1000, 1L);
        }
        assertTrue(granted <= 100, "granted " + granted + " against a ceiling of 100");
        assertTrue(pool.cancelling(), "and it noticed that it refused somebody");
    }

    @Test
    @DisplayName("the body that ticks last is not the body that goes blind")
    void fairnessDoesNotDependOnTickOrder() {
        ReadPool pool = pool(100, 10);
        Object greedy = new Object();
        Object patient = new Object();
        // The greedy one ticks first every tick and always asks for everything.
        int patientTotal = 0;
        for (long tick = 1; tick <= 20; tick++) {
            pool.grant(greedy, 1000, tick);
            patientTotal += pool.grant(patient, 10, tick);
        }
        assertTrue(patientTotal >= 150,
                "the one at the end of the tick order still got served — got " + patientTotal);
    }

    @Test
    @DisplayName("a body that stood still can spend hard when it walks into a wood")
    void quietTicksBankForLoudOnes() {
        ReadPool pool = pool(1000, 10); // share = 100 a tick
        Object body = new Object();
        // How the sensor really behaves: it asks for its whole wallet every tick and then hands
        // back what it did not need, because it cannot know in advance how little that will be.
        for (long tick = 1; tick <= 10; tick++) {
            int granted = pool.grant(body, 256, tick);
            pool.refund(body, granted, tick); // knew the ground, read nothing
        }
        int burst = pool.grant(body, 10_000, 11L);
        assertTrue(burst > 100, "banked more than one tick's share — got " + burst);
        assertTrue(burst <= 100 * ReadPool.BURST_TICKS + 100,
                "but not an unbounded hoard — got " + burst);
    }

    @Test
    @DisplayName("an idle crowd does not hold a busy body out of a budget nobody is using")
    void refundsFreeTheCeilingForWhoeverNeedsIt() {
        ReadPool pool = pool(1000, 10);
        // Nine bodies ask for a full wallet and read nothing at all.
        for (int i = 0; i < 9; i++) {
            Object idle = new Object();
            int granted = pool.grant(idle, 256, 1L);
            pool.refund(idle, granted, 1L);
        }
        // The tenth walked into a wood on the same tick.
        int busy = pool.grant(new Object(), 1000, 1L);
        assertTrue(busy >= 100,
                "the ceiling had room because the idle ones gave theirs back — got " + busy);
    }

    @Test
    @DisplayName("a refund from a tick that has already turned over is not credited")
    void staleRefundsAreIgnored() {
        ReadPool pool = pool(1000, 10);
        Object body = new Object();
        pool.grant(body, 100, 1L);
        pool.grant(body, 0, 2L);          // the tick rolls over
        pool.refund(body, 100, 1L);       // too late
        int next = pool.grant(body, 10_000, 2L);
        assertTrue(next <= 100 * ReadPool.BURST_TICKS + 100,
                "a stale refund did not inflate the bank — got " + next);
    }

    @Test
    @DisplayName("what one body is refused is still there for the others")
    void refusalIsNotWaste() {
        ReadPool pool = pool(100, 2);
        Object first = new Object();
        Object second = new Object();
        int a = pool.grant(first, 1000, 1L);
        int b = pool.grant(second, 1000, 1L);
        assertTrue(a > 0 && b > 0, "both were served: " + a + " and " + b);
        assertTrue(a + b <= 100);
    }

    @Test
    @DisplayName("a ceiling of zero is no ceiling — the old per-agent behaviour, exactly")
    void zeroIsUnmetered() {
        ReadPool pool = pool(0, 500);
        assertEquals(256, pool.grant(new Object(), 256, 1L));
        assertEquals(256, pool.grant(new Object(), 256, 1L));
        assertFalse(pool.cancelling(), "an unmetered pool never refuses anybody");
    }

    @Test
    @DisplayName("a hitch credits once, not once per tick it slept through")
    void skippedTicksCreditOnce() {
        ReadPool pool = pool(1000, 10); // share = 100
        Object body = new Object();
        pool.grant(body, 0, 1L);
        int afterALongGap = pool.grant(body, 10_000, 1000L);
        assertTrue(afterALongGap <= 100 * ReadPool.BURST_TICKS + 100,
                "a thousand skipped ticks did not become a thousand ticks of credit — got "
                        + afterALongGap);
    }

    @Test
    @DisplayName("the share falls as the population grows — that IS the protection")
    void shareFollowsPopulation() {
        ReadPool pool = pool(1000, 10);
        assertEquals(100, pool.share());
        pool.population(500);
        assertEquals(2, pool.share());
        pool.population(5000);
        assertEquals(1, pool.share(), "never zero: a body always gets to look at something");
    }
}
