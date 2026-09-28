package dev.luizloyola.anima.core.brain.act;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.act.WeaponChoice.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link WeaponChoice}'s rules, with vanilla's numbers: a fist is 1 damage at 4 swings a second
 * (4/s), a wooden sword 4 at 1.6 (6.4/s), a wooden axe 7 at 0.8 (5.6/s), an iron sword 6 at 1.6
 * (9.6/s), a wooden shovel 2.5 at 1 (2.5/s), and a log hits as a fist does.
 */
class WeaponChoiceTest {

    private static final double FIST = 4.0;
    private static final int HAND = 0;

    @Test
    void theMostDamagePerSecondWins() {
        List<Candidate> pack = List.of(new Candidate(4, 6.4), new Candidate(9, 5.6));
        assertEquals(4, WeaponChoice.choose(pack, HAND, FIST));
    }

    @Test
    void aWeaponAlreadyInHandStays() {
        List<Candidate> pack = List.of(new Candidate(HAND, 9.6), new Candidate(12, 6.4));
        assertEquals(ToolChoice.KEEP_HAND, WeaponChoice.choose(pack, HAND, FIST));
    }

    @Test
    void aTieKeepsTheHandThenTheLowestSlot() {
        assertEquals(ToolChoice.KEEP_HAND, WeaponChoice.choose(
                List.of(new Candidate(3, 6.4), new Candidate(HAND, 6.4)), HAND, FIST));
        assertEquals(3, WeaponChoice.choose(
                List.of(new Candidate(8, 6.4), new Candidate(3, 6.4)), HAND, FIST));
    }

    @Test
    void somethingThatHitsWorseThanAFistIsPutAway() {
        List<Candidate> pack = List.of(new Candidate(HAND, 2.5));
        assertEquals(ToolChoice.BARE_HAND, WeaponChoice.choose(pack, HAND, FIST));
    }

    @Test
    void somethingThatHitsLikeAFistStaysRatherThanCostAWarmup() {
        List<Candidate> pack = List.of(new Candidate(HAND, FIST), new Candidate(5, 2.5));
        assertEquals(ToolChoice.KEEP_HAND, WeaponChoice.choose(pack, HAND, FIST));
    }

    @Test
    void anEmptyHandWithNothingBetterStaysEmpty() {
        assertEquals(ToolChoice.KEEP_HAND,
                WeaponChoice.choose(List.of(new Candidate(5, 2.5)), HAND, FIST));
        assertEquals(ToolChoice.KEEP_HAND, WeaponChoice.choose(List.of(), HAND, FIST));
    }
}
