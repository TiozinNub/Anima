package dev.luizloyola.anima.mod.webdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The clock set, driven by a clock the test holds — {@code nanoTime} is compared by difference, so
 * any origin will do and a far-from-zero one catches a reading of an uninitialised field.
 */
class WebClocksTest {

    /** Somewhere far from zero, so a bug reading an unarmed deadline does not accidentally pass. */
    private static final long START = 1_000_000_000_000L;

    private static long millis(long count) {
        return count * 1_000_000L;
    }

    @Test
    @DisplayName("every clock is due on the first tick, so the first frame is a whole world")
    void everyClockStartsDue() {
        WebClocks clocks = new WebClocks();
        assertEquals(EnumSet.allOf(WebClock.class), clocks.due(START));
    }

    @Test
    @DisplayName("a slow clock stays quiet while a fast one fires")
    void ratesAreIndependent() {
        WebClocks clocks = new WebClocks();
        clocks.due(START);

        // 60 ms on: health (20/s, every 50) is due again, roster (10/s, every 100) is not.
        EnumSet<WebClock> due = clocks.due(START + millis(60));
        assertTrue(due.contains(WebClock.HEALTH));
        assertFalse(due.contains(WebClock.ROSTER));
        assertFalse(due.contains(WebClock.SLOW));
    }

    @Test
    @DisplayName("a forced clock fires whether or not its deadline had come, once")
    void forcingFiresOnce() {
        WebClocks clocks = new WebClocks();
        clocks.due(START);

        clocks.force(WebClock.DETAIL);
        EnumSet<WebClock> due = clocks.due(START + millis(1));
        assertTrue(due.contains(WebClock.DETAIL), "a click must not wait out the clock");

        // And it is spent: the next tick is back on the ordinary schedule.
        assertFalse(clocks.due(START + millis(2)).contains(WebClock.DETAIL));
    }

    @Test
    @DisplayName("a heartbeat is owed a second after the last frame went, and not before")
    void heartbeatFloor() {
        WebClocks clocks = new WebClocks();
        clocks.published(START);

        assertFalse(clocks.beat(START + millis(999)));
        assertTrue(clocks.beat(START + millis(1_000)));
    }

    @Test
    @DisplayName("a feed that has never published owes a heartbeat immediately")
    void heartbeatBeforeAnyFrame() {
        assertTrue(new WebClocks().beat(START));
    }

    @Test
    @DisplayName("the clocks' rates are pinned — they are what the browser's cadence comes out as")
    void ratesAreLiteral() {
        // Rates are wire-bound: every pace is built from one, so a change here is a change in how
        // often the dashboard moves. Which key rides which clock is NOT pinned here — WebSnapshot
        // .build is the only place that mapping exists, and a test of a second copy of it would
        // only ever confirm the copy.
        assertEquals(20, WebClock.HEALTH.perSecond());
        assertEquals(10, WebClock.ROSTER.perSecond());
        assertEquals(4, WebClock.DETAIL.perSecond());
        assertEquals(60, WebClock.CHART.perSecond());
        assertEquals(2, WebClock.SLOW.perSecond());
    }
}
