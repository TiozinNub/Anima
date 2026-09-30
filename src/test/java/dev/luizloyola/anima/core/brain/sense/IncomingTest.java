package dev.luizloyola.anima.core.brain.sense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Incoming}: hurt immunity lets one full blow through every 10 ticks, whoever deals it. */
class IncomingTest {

    private static Combatant mob(double damage, double intervalTicks, boolean piercing, int linger) {
        return new Combatant(20, 20, 2, 0, damage, 20.0 / intervalTicks, 0.1, 0, 0,
                Combatant.Entry.WALKS, false, false, piercing, linger);
    }

    private static Combatant zombie() {
        return mob(3, 20, false, 0);
    }

    @Test
    void oneZombieDealsItsBlowEverySecond() {
        assertEquals(3.0, Incoming.perSecond(List.of(zombie()), 0, 0), 0.1);
    }

    @Test
    void fiveZombiesDealOneBlowEveryTenTicksNotFifteenASecond() {
        double five = Incoming.perSecond(Collections.nCopies(5, zombie()), 0, 0);
        assertTrue(five > 4.0 && five <= 6.0 + 1e-9,
                "at most 3 per 10 ticks however many, less when their blows fall out of step: " + five);
    }

    @Test
    void twoZombiesOutOfStepAlreadyReachTheCap() {
        double two = Incoming.perSecond(Collections.nCopies(2, zombie()), 0, 0);
        assertTrue(two > 3.0 && two <= 6.0 + 1e-9, "between one zombie and the cap: " + two);
    }

    @Test
    void armourCutsABlowButNotWhatPierces() {
        double plain = Incoming.perSecond(List.of(mob(6, 60, false, 0)), 20, 8);
        double harming = Incoming.perSecond(List.of(mob(6, 60, true, 0)), 20, 8);
        assertTrue(plain < harming);
        assertEquals(2.0, harming, 0.1);
    }

    @Test
    void poisonAddsWhatFallsOutsideTheBitesWindow() {
        double bite = Incoming.perSecond(List.of(mob(2, 20, false, 0)), 0, 0);
        double poisoned = Incoming.perSecond(List.of(mob(2, 20, false, 25)), 0, 0);
        assertTrue(poisoned > bite, bite + " then " + poisoned);
        assertTrue(poisoned < bite + 0.8, "some poison ticks are lost inside a bite's window");
    }

    @Test
    void contactEveryTickLandsOncePerWindow() {
        assertEquals(8.0, Incoming.perSecond(List.of(mob(4, 1, false, 0)), 0, 0), 0.1,
                "a big slime touching: 4 per 10 ticks");
    }

    @Test
    void nothingHittingIsNothing() {
        assertEquals(0.0, Incoming.perSecond(List.of(), 0, 0));
        assertEquals(0.0, Incoming.perSecond(List.of(mob(0, 20, false, 0)), 0, 0));
    }
}
