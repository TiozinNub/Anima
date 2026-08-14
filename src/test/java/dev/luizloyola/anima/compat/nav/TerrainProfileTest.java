package dev.luizloyola.anima.compat.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.compat.nav.TerrainProfile.Band;
import org.junit.jupiter.api.Test;

/**
 * How tall a nav capture makes itself: arithmetic over the band the walk's ends demand and the
 * band the ground between them occupies, which decides whether a route over a rise exists. Reading
 * the heightmaps needs a {@code Level} and is exercised in-world.
 */
class TerrainProfileTest {

    /** What the endpoints alone used to ask for: {@code -DOWN_MARGIN .. +UP_MARGIN} around them. */
    private static Band ends(int lowY, int highY) {
        return new Band(lowY - 10, highY + 6);
    }

    @Test
    void aRidgeAboveBothEndsIsInsideTheBand() {
        // The case that was measured in-world: ends at 86 and 90 got a ceiling of 96, and the only
        // crossing between them peaked at 97. One block, and the route did not exist.
        Band endpoints = ends(86, 90);
        assertEquals(96, endpoints.high(), "the old ceiling, for the record");

        Band widened = TerrainProfile.widen(endpoints, new Band(67, 100));
        assertTrue(widened.high() >= 97,
                "a crossing at 97 must be inside the capture, not above its ceiling: " + widened);
    }

    @Test
    void flatGroundIsLeftAloneBecauseThereIsNothingToReachOver() {
        // Where the terrain does not rise, nothing is added: the cost is paid where the ground varies.
        Band endpoints = ends(64, 64);
        Band widened = TerrainProfile.widen(endpoints, new Band(62, 66));
        assertEquals(endpoints.low(), widened.low(), "a floor 2 below the ends adds nothing");
        assertEquals(endpoints.high(), widened.high(), "a top 2 below the old ceiling adds nothing");
    }

    @Test
    void theBandOnlyEverGrows() {
        // Load-bearing: the endpoint band is every capture there used to be, so keeping it as the
        // floor of this calculation is what guarantees no walk that worked can stop working.
        Band endpoints = ends(90, 95);
        Band widened = TerrainProfile.widen(endpoints, new Band(88, 92));
        assertTrue(widened.low() <= endpoints.low(), "never raises the floor: " + widened);
        assertTrue(widened.high() >= endpoints.high(), "never lowers the ceiling: " + widened);
    }

    @Test
    void anUnreadableTerrainLeavesTheEndpointBandExactlyAsItWas() {
        Band endpoints = ends(70, 72);
        assertEquals(endpoints, TerrainProfile.widen(endpoints, null));
    }

    @Test
    void aMountainFarAboveTheWalkDoesNotDragTheCaptureUpToItsSummit() {
        // A summit seventy blocks over the two cells being joined is not on the way between them,
        // and capturing to it buys sky: the cap keeps a wide box from becoming a tall one too.
        Band endpoints = ends(70, 70);
        Band widened = TerrainProfile.widen(endpoints, new Band(64, 200));
        assertTrue(widened.high() <= endpoints.high() + 8,
                "the ceiling is bounded relative to the walk: " + widened);
        assertTrue(widened.low() >= endpoints.low() - 4,
                "and so is the floor: " + widened);
    }

    @Test
    void deepGroundBelowTheWalkIsReachedForButNotChasedToBedrock() {
        Band endpoints = ends(70, 70);
        Band widened = TerrainProfile.widen(endpoints, new Band(40, 72));
        assertTrue(widened.low() < endpoints.low(),
                "a valley below both ends is worth capturing: " + widened);
        assertTrue(widened.low() >= endpoints.low() - 4, "within the cap: " + widened);
    }

    @Test
    void aBandCannotBeInsideOut() {
        assertThrows(IllegalArgumentException.class, () -> new Band(10, 9));
        assertEquals(1, new Band(10, 10).span());
        assertEquals(11, new Band(0, 10).span());
    }
}
