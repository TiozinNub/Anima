package dev.luizloyola.anima.mod.webdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
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
    @DisplayName("the clocks' keys and rates are pinned to the wire")
    void keysAndRatesAreLiteral() {
        // These strings name top-level frame keys the browser reads; a typo is invisible until the
        // dashboard fails to parse. Rates are equally wire-bound: the browser's update cadence
        // depends on them.

        assertEquals(20, WebClock.HEALTH.perSecond());
        assertEquals(List.of("health"), WebClock.HEALTH.keys());

        assertEquals(10, WebClock.ROSTER.perSecond());
        assertEquals(List.of("agents"), WebClock.ROSTER.keys());

        assertEquals(4, WebClock.DETAIL.perSecond());
        assertEquals(List.of("detail"), WebClock.DETAIL.keys());

        assertEquals(4, WebClock.CHART.perSecond());
        assertEquals(List.of("samples"), WebClock.CHART.keys());

        assertEquals(2, WebClock.SLOW.perSecond());
        assertEquals(List.of("players", "layers", "actingAs", "dead"), WebClock.SLOW.keys());
    }
}
