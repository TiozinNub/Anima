package dev.luizloyola.anima.core.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.TestSpecies;
import org.junit.jupiter.api.Test;

/** Time away and distress both weigh; a minimum absence, unless the times are very bad (decision 17). */
class DeposingTest {

    private final AgentProfile weigher = TestSpecies.PROFILE; // threshold 30, very bad 40, two days

    @Test
    void aLeaderWhoIsThereIsNeverDeposed() {
        assertFalse(Deposing.deposes(0, 100, 100, weigher));
    }

    @Test
    void aShortAbsenceInBadTimesIsNotEnough() {
        assertFalse(Deposing.deposes(1, 35, 25, weigher), "a day away, discontent over the bar, but too soon");
    }

    @Test
    void aLongAbsenceWithDiscontentDeposes() {
        assertTrue(Deposing.deposes(2, 30, 10, weigher));
        assertFalse(Deposing.deposes(2, 25, 5, weigher), "away long, but nobody minds enough");
    }

    @Test
    void veryBadTimesDeposeHoweverShortTheAbsence() {
        assertTrue(Deposing.deposes(0.5, 45, 40, weigher));
    }
}
