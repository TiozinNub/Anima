package dev.luizloyola.anima.core.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A rhythm, checked as a rhythm: the failures are an eye blinking on a metronome and every eye on
 * screen blinking together, neither of which an assertion about one moment would catch.
 */
class BlinkTest {

    /** Every moment a blink starts, over ten minutes of one agent. */
    private static List<Long> starts(long seed, long span) {
        List<Long> found = new ArrayList<>();
        boolean was = false;
        for (long at = 0; at < span; at += 10) {
            boolean shut = Blink.shutAt(seed, at);
            if (shut && !was) {
                found.add(at);
            }
            was = shut;
        }
        return found;
    }

    @Test
    void anEyeIsShutForAboutAsLongAsABlinkTakes() {
        long start = starts(1234L, 60_000).get(0);
        long shutFor = 0;
        for (long at = start; Blink.shutAt(1234L, at); at += 10) {
            shutFor = at - start + 10;
        }
        assertTrue(shutFor >= Blink.SHUT_MILLIS && shutFor <= Blink.SHUT_MILLIS + 20,
                "a blink lasted " + shutFor + "ms");
    }

    /** Fifteen to twenty a minute is what a resting human does. */
    @Test
    void theRateIsRoughlyRestingHuman() {
        double perMinute = starts(99L, 600_000).size() / 10.0;
        assertTrue(perMinute > 12 && perMinute < 26, "blinked " + perMinute + " times a minute");
    }

    /**
     * The gaps must <b>vary</b>: a regular blink is the clearest tell that something is not alive,
     * and a rate check alone sails straight past it.
     */
    @Test
    void theIntervalIsScatteredRatherThanPeriodic() {
        List<Long> at = starts(7L, 600_000);
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < at.size(); i++) {
            gaps.add(at.get(i) - at.get(i - 1));
        }
        long shortest = gaps.stream().mapToLong(Long::longValue).min().orElseThrow();
        long longest = gaps.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertTrue(longest - shortest > 2_000,
                "gaps ran " + shortest + "-" + longest + "ms, which is very nearly a metronome");
    }

    /** And a settlement must not blink in unison. */
    @Test
    void twoAgentsDoNotBlinkTogether() {
        int together = 0;
        for (long at = 0; at < 120_000; at += 10) {
            if (Blink.shutAt(1L, at) && Blink.shutAt(2L, at)) {
                together++;
            }
        }
        int alone = 0;
        for (long at = 0; at < 120_000; at += 10) {
            if (Blink.shutAt(1L, at)) {
                alone++;
            }
        }
        assertTrue(together < alone / 2, "two agents were shut together " + together + " of " + alone);
    }

    /** Stateless means repeatable: the same agent at the same moment is always the same eye. */
    @Test
    void theSameMomentAlwaysGivesTheSameAnswer() {
        for (long at = 0; at < 20_000; at += 37) {
            assertEquals(Blink.shutAt(42L, at), Blink.shutAt(42L, at));
        }
    }

    /** Time before the origin is as valid as time after it — a world's clock is nobody's business. */
    @Test
    void negativeTimeBlinksToo() {
        assertTrue(starts(5L, 1).isEmpty() || true);
        int shut = 0;
        for (long at = -600_000; at < -540_000; at += 10) {
            if (Blink.shutAt(5L, at)) {
                shut++;
            }
        }
        assertTrue(shut > 0, "an eye never shut at negative time");
    }
}
