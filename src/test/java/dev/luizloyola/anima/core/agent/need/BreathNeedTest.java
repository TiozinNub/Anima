package dev.luizloyola.anima.core.agent.need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.TestSpecies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Breath: a reading over somebody else's number, with the steepest ramp on the roster. The
 * suppliers are the seam the real one uses — {@code core/} never sees an entity, so a test needs
 * none either.
 */
class BreathNeedTest {

    private static final double DELTA = 1e-9;

    /** A body with {@code air} ticks left of a vanilla 300-tick lungful. */
    private static BreathNeed body(int[] air) {
        return new BreathNeed(() -> air[0], () -> 300, () -> TestSpecies.PROFILE);
    }

    @Test
    @DisplayName("air is a VIEW — one number, and the gauge holds none of it")
    void readsThroughToTheBody() {
        int[] air = {300};
        BreathNeed breath = body(air);

        assertEquals(300.0, breath.value(), DELTA, "just surfaced: a full lungful");
        assertEquals(0.0, breath.pressure(), DELTA, "and nothing to ask for");
        assertEquals("easy", breath.level().key());

        air[0] = 100;
        assertEquals(100.0, breath.value(), DELTA, "the gauge moved because the BODY did");
        assertEquals("short", breath.level().key());
    }

    @Test
    @DisplayName("the reading stops at empty, though the game's counter does not")
    void negativeAirReadsAsEmpty() {
        int[] air = {-13}; // vanilla runs on past zero and uses the negatives as a damage timer
        BreathNeed breath = body(air);

        assertEquals(0.0, breath.value(), DELTA,
                "below zero a body is not MORE out of air; it is out of air and being hurt");
        assertEquals("drowning", breath.level().key());
        assertEquals(1.0, breath.pressure(), DELTA);
    }

    /**
     * Against hunger's own {@code starving} LEVEL, not against an empty bar: the ramp pins any axis
     * end no level anchors at full pressure, so hunger at 0 and breath at 0 both read 1.0 by
     * construction. Two needs at 1.0 is a tie the arbiter has to break some other way.
     */
    @Test
    @DisplayName("the ramp is steep where it matters — gasping outbids a starving body")
    void gaspingOutbidsEverySlowerNeed() {
        int[] air = {60};
        double gasping = body(air).pressure();

        double starving = NeedKind.HUNGER.levels().stream()
                .filter(level -> level.key().equals("starving")).findFirst().orElseThrow()
                .pressure(TestSpecies.PROFILE);
        assertTrue(gasping > starving,
                "three seconds from drowning (" + gasping + ") must outbid starving ("
                        + starving + "), or a settler finishes lunch on the lakebed");
    }

    @Test
    @DisplayName("pressure rises the whole way down, not only inside a band")
    void pressureGlidesBetweenLevels() {
        int[] air = {300};
        BreathNeed breath = body(air);
        double previous = -1.0;
        for (int left = 300; left >= 0; left -= 10) {
            air[0] = left;
            double now = breath.pressure();
            assertTrue(now >= previous,
                    "pressure fell while the air did, at " + left + ": " + previous + " -> " + now);
            previous = now;
        }
        assertEquals(1.0, previous, DELTA, "empty is as urgent as this need gets");
    }

    @Test
    @DisplayName("a body reports its real lungful, so a mismatched one is visible in a readout")
    void describeShowsTheBodysOwnFullMark() {
        BreathNeed odd = new BreathNeed(() -> 150, () -> 600, () -> TestSpecies.PROFILE);
        assertTrue(odd.describe().contains("/600"),
                "the levels are calibrated against a 300-tick axis; a body with another lungful "
                        + "must not be able to hide it: " + odd.describe());
    }

    @Test
    @DisplayName("ticking does nothing — the game owns this number, not us")
    void tickingLeavesItAlone() {
        int[] air = {120};
        BreathNeed breath = body(air);
        for (int i = 0; i < 100; i++) {
            breath.tick();
        }
        assertEquals(120.0, breath.value(), DELTA);
    }
}
