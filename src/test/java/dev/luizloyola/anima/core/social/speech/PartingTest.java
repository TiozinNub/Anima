package dev.luizloyola.anima.core.social.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The player's trailed-off clock: who left, after how long, and what clears it. */
class PartingTest {

    private static final int GRACE = 40;
    private static final int PATIENCE = 300;

    private final Parting parting = new Parting();

    @Test
    @DisplayName("apart is not leaving until the grace has run, and coming back clears it")
    void selfLeavingWaitsOutTheGrace() {
        assertEquals(Parting.Verdict.NONE, parting.tick(0, true, false, GRACE, PATIENCE), "stamped");
        assertEquals(Parting.Verdict.NONE, parting.tick(GRACE, true, false, GRACE, PATIENCE), "inclusive");
        assertEquals(Parting.Verdict.NONE, parting.tick(GRACE + 5, false, false, GRACE, PATIENCE), "back");
        assertEquals(Parting.Verdict.NONE, parting.tick(GRACE + 10, true, false, GRACE, PATIENCE),
                "apart again — the clock starts over");
        assertEquals(Parting.Verdict.SELF_LEFT, parting.tick(2 * GRACE + 11, true, false, GRACE, PATIENCE));
    }

    @Test
    @DisplayName("a counterpart gone is let go after patience, not after the grace")
    void otherGoneWaitsOutPatience() {
        parting.tick(0, false, true, GRACE, PATIENCE);
        assertEquals(Parting.Verdict.NONE, parting.tick(GRACE + 1, false, true, GRACE, PATIENCE),
                "the short clock is the leaver's, not theirs");
        assertEquals(Parting.Verdict.OTHER_GONE, parting.tick(PATIENCE + 1, false, true, GRACE, PATIENCE));
    }

    @Test
    @DisplayName("a verdict clears the stamp — the next parting starts from zero")
    void aVerdictClearsTheStamp() {
        parting.tick(0, true, false, GRACE, PATIENCE);
        assertEquals(Parting.Verdict.SELF_LEFT, parting.tick(GRACE + 1, true, false, GRACE, PATIENCE));
        // Still apart a tick later: with the stamp cleared this is a fresh stamp, not a second
        // verdict — the clock that outlived its record closed the next one on its first line.
        assertEquals(Parting.Verdict.NONE, parting.tick(GRACE + 2, true, false, GRACE, PATIENCE));
    }

    @Test
    @DisplayName("reset is a fresh record: whatever was running never ran")
    void resetStartsTogether() {
        parting.tick(0, true, false, GRACE, PATIENCE);
        parting.reset();
        assertEquals(Parting.Verdict.NONE, parting.tick(1_000, true, false, GRACE, PATIENCE), "stamped anew");
    }
}
