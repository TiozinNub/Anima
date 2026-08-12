package dev.luizloyola.anima.mod.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.nav.MoveType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * When a body may claim to have <em>reached</em> a waypoint by height — {@link
 * Navigator#atWaypointHeight}, the rule the skip and the plane-advance both consult.
 *
 * <p>Water is the interesting half: a floating body bobs a whole block, so a water waypoint takes a
 * wide band rather than the tight standing band a footed move uses. That band alone is also
 * satisfied by a body standing on the BANK a block above the surface — which is how a dry Person on
 * a pool rim claimed the water cell below her, was handed a dive, and failed the walk.
 */
class NavigatorReachTest {

    /** The bob a swimmer really does, in blocks — comfortably inside the band. */
    private static final double BOB = 0.9;
    /** High above a waypoint, in blocks: inside the swimmer's band, outside the standing one. */
    private static final double A_BOB_TOO_FAR = 1.4;

    @Test
    void aSwimmerClaimsItsWaterCellRightThroughTheBob() {
        assertTrue(Navigator.atWaypointHeight(BOB, MoveType.SWIM, true), "riding high on the bob");
        assertTrue(Navigator.atWaypointHeight(-BOB, MoveType.SWIM, true), "dipped under it");
    }

    @Test
    void aDryBodyNeverClaimsAWaterCell() {
        // Gloria on the pool rim: a whole block above the surface cell, dry, and well inside the
        // band the bob needs. Being in the right column is not being in the water.
        for (MoveType water : List.of(MoveType.SWIM, MoveType.DIVE, MoveType.SURFACE)) {
            assertFalse(Navigator.atWaypointHeight(1.0, water, false),
                    water + " must not be claimed from the bank above it");
        }
    }

    @Test
    void beingWetChangesNothingForAFootedMove() {
        // Wading and climbing out are footed moves that happen while wet: the wetness gate is about
        // water CELLS, and must not loosen or tighten the standing band of a move onto ground.
        for (boolean wet : List.of(true, false)) {
            assertTrue(Navigator.atWaypointHeight(0.2, MoveType.WALK, wet));
            assertFalse(Navigator.atWaypointHeight(A_BOB_TOO_FAR, MoveType.WALK, wet),
                    "a walker well above its ledge has not reached it, wet or dry");
        }
    }

    /**
     * A descent parks the body inside its target cell (the depth hold brakes early and lifts off the
     * floor), so a dive keeps the tight band — a swimmer's wide one would let a descent claim a cell
     * it is still most of a block short of, and this body already ratchets down a column too easily.
     */
    @Test
    void aDiveKeepsTheTightBandEvenInTheWater() {
        assertTrue(Navigator.atWaypointHeight(0.3, MoveType.DIVE, true));
        assertFalse(Navigator.atWaypointHeight(A_BOB_TOO_FAR, MoveType.DIVE, true));
        assertTrue(Navigator.atWaypointHeight(A_BOB_TOO_FAR, MoveType.SWIM, true),
                "the same height a swim waypoint accepts — the two bands really do differ");
    }
}
