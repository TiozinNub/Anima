package dev.luizloyola.anima.core.brain.act;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.act.WeaponChoice.Candidate;
import dev.luizloyola.anima.core.brain.act.WeaponChoice.Foe;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link WeaponChoice}'s rules, with vanilla's numbers: a fist is 1 damage at 4 swings a second
 * (4/s), a wooden sword 4 at 1.6 (6.4/s), an iron sword 6 at 1.6 (9.6/s), an iron axe 9 at 0.9
 * (8.1/s), a wooden shovel 2.5 at 1 (2.5/s), and a log hits as a fist does. A zombie has 20 health
 * and 2 armour: 4 blows of an iron sword, 6 of a wooden one.
 */
class WeaponChoiceTest {

    private static final int HAND = 0;
    private static final int NEVER = WeaponChoice.UNBREAKING;
    private static final Candidate FIST = new Candidate(ToolChoice.BARE_HAND, 1.0, 4.0, NEVER);
    private static final Foe ZOMBIE = new Foe(20.0, 2.0, 0.0);
    /** A backpack pull: six ticks. */
    private static final double SWAP = 0.3;

    private static Candidate woodenSword(int slot) {
        return new Candidate(slot, 4.0, 1.6, 59);
    }

    private static Candidate ironSword(int slot, int blowsLeft) {
        return new Candidate(slot, 6.0, 1.6, blowsLeft);
    }

    private static Candidate ironAxe(int slot, int blowsLeft) {
        return new Candidate(slot, 9.0, 0.9, blowsLeft);
    }

    private static Candidate shovel(int slot) {
        return new Candidate(slot, 2.5, 1.0, 29);
    }

    private static int choose(List<Candidate> pack, Foe foe) {
        return WeaponChoice.choose(pack, HAND, FIST, foe, SWAP);
    }

    @Test
    void theQuickestKillWins() {
        assertEquals(4, choose(List.of(ironSword(4, 250), woodenSword(9)), ZOMBIE));
        assertEquals(4, choose(List.of(ironSword(4, 250), woodenSword(9)), null));
    }

    @Test
    void anAxeThatHitsHarderIsDrawnOverABadSword() {
        assertEquals(12, choose(List.of(woodenSword(4), ironAxe(12, 125)), ZOMBIE));
    }

    @Test
    void theHitThatCountsAgainstThisTargetDecides() {
        // A sword whose enchantment adds against this target measures a bigger hit than a plain one.
        Candidate smite = new Candidate(7, 6.0 + 7.5, 1.6, 250);
        assertEquals(7, choose(List.of(ironSword(3, 250), smite), ZOMBIE));
    }

    @Test
    void aWornWeaponThatCanFinishTheFightRanksAsAFreshOne() {
        assertEquals(3, choose(List.of(ironSword(3, 4), ironSword(5, 250)), ZOMBIE));
    }

    @Test
    void aWeaponThatWouldBreakFirstIsWorthOnlyItsBlowsLeft() {
        assertEquals(5, choose(List.of(ironSword(3, 1), ironSword(5, 250)), ZOMBIE));
        assertEquals(5, choose(List.of(ironAxe(3, 1), ironAxe(5, 125)), ZOMBIE));
    }

    @Test
    void itsBlowsLeftThenTheNextBestCanStillBeatTheNextBestAlone() {
        // Two iron blows, the swap, then three wooden ones: 3.4 s. Six wooden blows: 3.75 s.
        assertEquals(3, choose(List.of(ironSword(3, 2), woodenSword(5)), ZOMBIE));
    }

    @Test
    void theDrawIsPartOfTheKill() {
        Candidate held = woodenSword(HAND);
        Candidate packed = new Candidate(20, 6.0, 1.6, 250, SWAP); // an iron sword in the backpack
        Foe nearlyDead = new Foe(3.0, 2.0, 0.0);

        assertEquals(ToolChoice.KEEP_HAND, choose(List.of(held, packed), nearlyDead),
                "one wooden blow now beats one iron blow after the pull");
        assertEquals(20, choose(List.of(held, packed), ZOMBIE), "a whole fight repays the pull");
    }

    @Test
    void withNoTargetWearIsNotWeighed() {
        assertEquals(3, choose(List.of(ironSword(3, 1), woodenSword(5)), null));
    }

    @Test
    void aWeaponAlreadyInHandStays() {
        assertEquals(ToolChoice.KEEP_HAND,
                choose(List.of(ironSword(HAND, 250), woodenSword(12)), ZOMBIE));
    }

    @Test
    void aTieKeepsTheHandThenTheLowestSlot() {
        assertEquals(ToolChoice.KEEP_HAND,
                choose(List.of(woodenSword(3), woodenSword(HAND)), ZOMBIE));
        assertEquals(3, choose(List.of(woodenSword(8), woodenSword(3)), ZOMBIE));
    }

    @Test
    void somethingThatHitsWorseThanAFistIsPutAway() {
        assertEquals(ToolChoice.BARE_HAND, choose(List.of(shovel(HAND)), ZOMBIE));
        assertEquals(ToolChoice.BARE_HAND, choose(List.of(shovel(HAND)), null));
    }

    @Test
    void somethingThatHitsLikeAFistStaysRatherThanCostAWarmup() {
        Candidate log = new Candidate(HAND, 1.0, 4.0, NEVER);
        assertEquals(ToolChoice.KEEP_HAND, choose(List.of(log, shovel(5)), ZOMBIE));
    }

    @Test
    void anEmptyHandWithNothingBetterStaysEmpty() {
        assertEquals(ToolChoice.KEEP_HAND, choose(List.of(shovel(5)), ZOMBIE));
        assertEquals(ToolChoice.KEEP_HAND, choose(List.of(), ZOMBIE));
    }
}
