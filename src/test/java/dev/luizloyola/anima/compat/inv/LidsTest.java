package dev.luizloyola.anima.compat.inv;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The edge the creak rides on. Everything else in {@link Lids} needs a live level; this is the part
 * that decides whether a container actually changed state, and it is where getting it wrong is
 * audible.
 */
class LidsTest {

    @Test
    void aBodyOnItsOwnCreaksAtBothEnds() {
        assertTrue(Lids.flips(0, 0, 1), "nobody had it open, now somebody does");
        assertTrue(Lids.flips(0, 1, 0), "and the last one out shuts it");
    }

    @Test
    void asecondBodyArrivesAndLeavesInSilence() {
        assertFalse(Lids.flips(0, 1, 2), "it was already open — this is not an opening");
        assertFalse(Lids.flips(0, 2, 1), "and one of two stepping back leaves it open");
    }

    @Test
    void aPlayerAlreadyInTheChestSilencesUsBothWays() {
        // The bug this pins. Vanilla's ContainerOpenersCounter holds players and we cannot join
        // it, so OUR count going 0 -> 1 says nothing about whether the container opened.
        assertFalse(Lids.flips(1, 0, 1), "the lid is already up; a second creak is a lie");
        assertFalse(Lids.flips(1, 1, 0), "and stepping back must not slam it in their face");
    }
}
