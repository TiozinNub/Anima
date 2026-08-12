package dev.luizloyola.anima.mod.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.nav.MoveType;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * The two places the follower reasons about <em>where a body is</em> rather than where its centre
 * is: {@link Navigator#atWaypointHeight} and {@link Navigator#columnsUnder}.
 *
 * <p>A floating body bobs a whole block, so a water waypoint takes a wide band, not the tight
 * standing band of a footed move — and a body on the BANK one block above the surface satisfies it
 * too. Caught live: a dry Person claimed the water cell below her, was handed a dive under solid
 * ground, and the walk failed after four retries.
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

    /** A Person's footprint, centred on {@code (x, z)} — 0.6 wide, so it spans at most two cells. */
    private static AABB footprint(double x, double z) {
        return new AABB(x - 0.3, -40.0, z - 0.3, x + 0.3, -38.2, z + 0.3);
    }

    private static List<String> columns(AABB box) {
        return Navigator.columnsUnder(box).stream().map(c -> c[0] + "," + c[2]).toList();
    }

    @Test
    void aBodyWellInsideACellStandsInThatCellAlone() {
        assertEquals(List.of("1110,106"), columns(footprint(1110.5, 106.5)));
    }

    /**
     * Gloria on the pool rim: centre over water at x=1110, 0.08 of her box on the deck at x=1111.
     * The deck holds her up, so its column must be in the list at all.
     */
    @Test
    void aBodyOverhangingALipOffersBothColumnsCentreFirst() {
        assertEquals(List.of("1110,106", "1111,106"), columns(footprint(1110.7825, 106.664)));
    }

    @Test
    void aBodyOverACornerOffersFourColumnsNearestFirst() {
        List<String> found = columns(footprint(1110.95, 106.95));
        assertEquals(4, found.size(), "a corner straddles two cells on each axis");
        assertEquals("1110,106", found.get(0), "the centre column is always tried first");
        assertTrue(found.containsAll(List.of("1111,106", "1110,107", "1111,107")));
        assertEquals("1111,107", found.get(3), "the diagonal is the farthest and so the last resort");
    }

    /**
     * Flush against a boundary: the box edge lands exactly on x=1111.0, touched but not stood in.
     * Claiming it would move an agent a cell sideways on a rounding error — why the footprint is
     * shrunk first.
     */
    @Test
    void aBoxThatMerelyGrazesTheNextCellDoesNotClaimIt() {
        assertEquals(List.of("1110,106"), columns(footprint(1110.7, 106.5)));
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
