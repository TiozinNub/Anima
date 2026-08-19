package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * When a body asks again whether it is shut in.
 *
 * <p>Two rules, and the second is the one that bites. <b>Asymmetric</b>: the prompt cadence exists
 * so a body which has just cut its way out stops digging, and that body is the SEALED one — a free
 * body has nothing to react to, so it waits. <b>Offset per agent</b>: the old cadence keyed off
 * {@code Entity.tickCount}, which starts at zero for every entity in a chunk load, so a hundred
 * bodies asked on the same tick forever after.
 */
class ConfinementCadenceTest {

    private static final Confinement FREE = new Confinement(false, 400);
    private static final Confinement SHUT_IN = new Confinement(true, 9);

    /** A body that has just proved it can get out is in no hurry to prove it again. */
    @Test
    void aFreeBodyAsksOnTheLongInterval() {
        ConfinementCadence cadence = new ConfinementCadence(1234L);
        int onSlot = onItsOwnSlot(cadence);
        cadence.ran(onSlot, FREE);

        assertFalse(cadence.due(onSlot + ConfinementCadence.FREE_TICKS - 1), "not yet");
        assertTrue(cadence.due(onSlot + ConfinementCadence.FREE_TICKS), "a full window later");
    }

    /**
     * The one case the short interval was written for: a body cutting its way out must notice it
     * is out within a second, or it carries on digging out of habit.
     */
    @Test
    void aShutInBodyKeepsTheShortInterval() {
        ConfinementCadence cadence = new ConfinementCadence(1234L);
        int onSlot = onItsOwnSlot(cadence);
        cadence.ran(onSlot, SHUT_IN);

        assertFalse(cadence.due(onSlot + ConfinementCadence.SEALED_TICKS - 1), "not yet");
        assertTrue(cadence.due(onSlot + ConfinementCadence.SEALED_TICKS), "a full window later");
    }

    /**
     * An ask forced off-slot — the tick a chunk load or a resumed brain lands on — re-aligns
     * rather than shifting the slot, so it comes due SOONER than a full window, never later. That
     * is what stops a group from carrying a shared tick forward for good.
     */
    @Test
    void anAskForcedOffSlotReAlignsRatherThanShiftingTheSlot() {
        ConfinementCadence cadence = new ConfinementCadence(1234L);
        int offSlot = onItsOwnSlot(cadence) + 7;
        cadence.ran(offSlot, FREE);

        int next = firstDueAfter(cadence, offSlot);
        assertTrue(next - offSlot <= ConfinementCadence.FREE_TICKS,
                "never later than a window: " + (next - offSlot));
        assertEquals(cadence.phase(),
                Math.floorMod(next, ConfinementCadence.FREE_TICKS), "back on its own slot");
    }

    /** A tick this agent's own cadence lands on, far enough in that the window is a full one. */
    private static int onItsOwnSlot(ConfinementCadence cadence) {
        return cadence.phase() + 10 * ConfinementCadence.FREE_TICKS;
    }

    /** Two bodies do not ask on the same tick, which is the whole point of the offset. */
    @Test
    void bodiesWithDifferentIdentitiesAskOnDifferentTicks() {
        Set<Integer> slots = new HashSet<>();
        for (long seed = 0; seed < 60; seed++) {
            ConfinementCadence cadence = new ConfinementCadence(seed);
            cadence.ran(0, FREE);
            int due = firstDueAfter(cadence, 0);
            slots.add(due % ConfinementCadence.FREE_TICKS);
        }
        assertTrue(slots.size() > 30,
                "60 bodies should land across the window, not on a handful of ticks: " + slots.size());
    }

    /**
     * The case that re-armed the spike in the world: a chunk load, or autonomy coming back on,
     * makes every body ask at once. The NEXT ask has to spread them again — a cadence that just
     * adds the interval would keep them in lockstep for good.
     */
    @Test
    void bodiesForcedOntoOneTickSpreadAgainOnTheNextAsk() {
        Set<Integer> slots = new HashSet<>();
        for (long seed = 0; seed < 60; seed++) {
            ConfinementCadence cadence = new ConfinementCadence(seed);
            cadence.ran(5000, FREE); // every body answered on the very same tick
            slots.add(firstDueAfter(cadence, 5000));
        }
        assertTrue(slots.size() > 30,
                "they must not all come due together again: " + slots.size());
    }

    /** An agent keeps its own slot: the offset is identity, not history. */
    @Test
    void thePhaseIsStableForOneIdentity() {
        assertEquals(new ConfinementCadence(77L).phase(), new ConfinementCadence(77L).phase());
    }

    /** Nothing has looked yet, so the first ask is owed inside one window rather than at once. */
    @Test
    void theFirstAskFallsInsideTheFirstWindow() {
        for (long seed = 0; seed < 40; seed++) {
            int due = firstDueAfter(new ConfinementCadence(seed), -1);
            assertTrue(due > 0 && due <= ConfinementCadence.FREE_TICKS, "first ask at " + due);
        }
    }

    /**
     * Bodies whose brains resume together — autonomy back on, thousands of ticks into their lives
     * — must not all answer on the resuming tick. The clock arms on first sight, it does not fire.
     */
    @Test
    void bodiesMeetingTheirFirstAskTogetherDoNotAnswerTogether() {
        Set<Integer> slots = new HashSet<>();
        for (long seed = 0; seed < 60; seed++) {
            ConfinementCadence cadence = new ConfinementCadence(seed);
            assertFalse(cadence.due(7777), "the first sight only arms the clock");
            slots.add(firstDueAfter(cadence, 7777));
        }
        assertTrue(slots.size() > 30, "resuming together must not mean asking together: " + slots.size());
    }

    private static int firstDueAfter(ConfinementCadence cadence, int from) {
        for (int now = from + 1; now < from + 1 + 4 * ConfinementCadence.FREE_TICKS; now++) {
            if (cadence.due(now)) return now;
        }
        throw new AssertionError("never came due");
    }
}
