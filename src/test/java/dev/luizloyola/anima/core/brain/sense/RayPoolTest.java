package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ceiling, and the fairness that is the only reason to have one: a first-come counter would
 * bound the total and still starve the tail, since agents tick in entity order.
 */
class RayPoolTest {

    /** A stand-in for a body: identity is all the pool uses. */
    private record Agent(String name) {
    }

    @Test
    @DisplayName("with room to spare, everybody gets exactly what they asked for")
    void aQuietServerNeverBinds() {
        RayPool pool = new RayPool(() -> 512);
        pool.population(5);

        for (long tick = 0; tick < 10; tick++) {
            for (int i = 0; i < 5; i++) {
                assertEquals(8, pool.grant(new Agent("a" + i), 8, tick),
                        "a five-agent world must behave exactly as it did before a pool existed");
            }
        }
        assertFalse(pool.cancelling(), "nothing was refused, so nothing should be reported");
    }

    @Test
    @DisplayName("the per-tick total never exceeds the ceiling, however many agents ask")
    void theCeilingIsHard() {
        RayPool pool = new RayPool(() -> 100);
        pool.population(50);

        int total = 0;
        for (int i = 0; i < 50; i++) {
            total += pool.grant(new Agent("a" + i), 64, 1L);
        }
        assertTrue(total <= 100, "granted " + total + " against a ceiling of 100");
        assertTrue(pool.cancelling(), "somebody was refused and that is worth an operator knowing");
    }

    @Test
    @DisplayName("an agent starved by tick order gets its turn on the next tick, not never")
    void whoeverWasCutOffIsWhoeverHasCreditNext() {
        // With a first-come counter, `hog` ticking first every tick would take everything and
        // `late` would never see anything.
        RayPool pool = new RayPool(() -> 20);
        pool.population(2);
        Agent hog = new Agent("hog");
        Agent late = new Agent("late");

        int lateTotal = 0;
        for (long tick = 0; tick < 20; tick++) {
            pool.grant(hog, 1000, tick);     // asks first, and asks for everything
            lateTotal += pool.grant(late, 10, tick);
        }

        assertTrue(lateTotal >= 150,
                "the agent that ticks second got " + lateTotal + " rays over 20 ticks; a "
                        + "first-come pool would have given it nothing");
    }

    @Test
    @DisplayName("asking under your share banks the difference, so a crowd can be met with a burst")
    void unusedCreditCarriesOverAndIsCapped() {
        RayPool pool = new RayPool(() -> 80);
        pool.population(8); // a share of 10 a tick
        Agent watcher = new Agent("watcher");

        // Quiet surroundings: the sense asks for its base, which is under the share.
        for (long tick = 0; tick < 10; tick++) {
            assertEquals(8, pool.grant(watcher, 8, tick));
        }
        // Then a wave arrives and it asks for everything it can use.
        int burst = pool.grant(watcher, 1000, 10L);

        assertTrue(burst > 10, "banked nothing over ten quiet ticks: " + burst);
        assertTrue(burst <= 10 * RayPool.BURST_TICKS,
                "banked more than the cap allows: " + burst);
    }

    @Test
    @DisplayName("the share follows the population, so the same ceiling stretches further")
    void theShareIsTheCeilingSplitEvenly() {
        RayPool pool = new RayPool(() -> 300);

        pool.population(3);
        assertEquals(100, pool.share());
        pool.population(300);
        assertEquals(1, pool.share());
        pool.population(3000);
        assertEquals(1, pool.share(), "never zero: a body with no rays at all would go blind");
    }

    @Test
    @DisplayName("the refused flag is an edge, cleared once read")
    void cancellingIsAnEdgeNotALevel() {
        RayPool pool = new RayPool(() -> 4);
        pool.population(1);

        pool.grant(new Agent("greedy"), 999, 1L);
        assertTrue(pool.cancelling());
        pool.clearCancelling();
        assertFalse(pool.cancelling(), "a level-triggered warning would be twenty lines a second");
    }

    @Test
    @DisplayName("a hitch does not bank a second's worth of rays to spend in one go")
    void skippedTicksCreditOnce() {
        RayPool pool = new RayPool(() -> 100);
        pool.population(10); // share of 10
        Agent body = new Agent("body");

        assertEquals(10, pool.grant(body, 10, 0L)); // spends its opening share exactly
        int afterHitch = pool.grant(body, 1000, 200L); // 200 ticks later — a long stall

        assertEquals(10, afterHitch,
                "one tick's accrual, not two hundred ticks' worth spent in one go on the tick "
                        + "the server can least afford it");
    }
}
