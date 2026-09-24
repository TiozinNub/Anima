package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.sense.Surroundings.DayPhase;
import org.junit.jupiter.api.Test;

/** The hour as a body tells it off the sky. */
class SurroundingsTest {

    @Test
    void theDayIsCutAtSunriseAndSunset() {
        assertEquals(DayPhase.DAWN, DayPhase.of(0), "0 is sunrise");
        assertEquals(DayPhase.DAWN, DayPhase.of(999));
        assertEquals(DayPhase.DAY, DayPhase.of(1_000));
        assertEquals(DayPhase.DAY, DayPhase.of(11_999));
        assertEquals(DayPhase.DUSK, DayPhase.of(12_000), "12,000 is sunset");
        assertEquals(DayPhase.NIGHT, DayPhase.of(13_500));
        assertEquals(DayPhase.NIGHT, DayPhase.of(22_999));
        assertEquals(DayPhase.DAWN, DayPhase.of(23_000));
    }

    @Test
    void anyDayOfTheWorldReadsTheSame() {
        assertEquals(DayPhase.of(6_000), DayPhase.of(24_000L * 40 + 6_000));
        assertEquals(DayPhase.NIGHT, DayPhase.of(-6_000), "a clock set back reads as last night");
    }
}
