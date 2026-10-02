package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A walk that may build, stranded for want of blocks, is told how many would have got it there
 * (Luiz, 2026-10-02: the amount comes from the solution that caused the failure).
 */
class PathfinderShortfallTest {

    private static NavGrid gap(int width) {
        String row = "111" + " ".repeat(width) + "111";
        return AsciiWorld.of(row, row, row).bounded();
    }

    private static Path find(NavGrid grid, int gx, MoveCapabilities body) {
        return Pathfinder.find(grid, PathRequest.of(1, 1, 1, gx, 1, 1, body));
    }

    @Test
    void anEmptyPocketIsToldWhatTheDecksTake() {
        Path path = find(gap(6), 10, TestBodies.BIPED.building(0));
        assertFalse(path.reachedGoal());
        assertEquals(0, path.laid(), "nothing is laid it does not carry");
        assertEquals(6, path.blocksNeeded());
    }

    @Test
    void aPocketTooShortIsToldTheWholeRouteNotTheDifference() {
        assertEquals(6, find(gap(6), 10, TestBodies.BIPED.building(4)).blocksNeeded());
    }

    @Test
    void aPocketThatIsEnoughCrossesAndNeedsNothing() {
        Path path = find(gap(6), 10, TestBodies.BIPED.building(6));
        assertTrue(path.reachedGoal());
        assertEquals(0, path.blocksNeeded());
    }

    @Test
    void aWalkThatMayNotBuildIsNeverToldToFetchBlocks() {
        assertEquals(0, find(gap(6), 10, TestBodies.BIPED.withLaid(0)).blocksNeeded());
    }

    @Test
    void noNumberOfBlocksSpansPastTheSpanCap() {
        assertEquals(0, find(gap(17), 21, TestBodies.BIPED.building(0)).blocksNeeded(),
                "a real dead end needs nothing fetched");
    }

    @Test
    void aGoalInTheAirNeedsNothing() {
        assertEquals(0, find(gap(6), 5, TestBodies.BIPED.building(0)).blocksNeeded());
    }

    @Test
    void aBodyThatCanWalkRoundIsNotStranded() {
        String[] rows = new String[21];
        for (int z = 0; z < 20; z++) {
            rows[z] = "1111    1111";
        }
        rows[20] = "111111111111";
        Path path = find(AsciiWorld.of(rows).bounded(), 10, TestBodies.BIPED.building(0));
        assertTrue(path.reachedGoal(), "the long way round");
        assertEquals(0, path.blocksNeeded());
    }

    /** Four up out of a pit is three pillar blocks and a jump. */
    @Test
    void aPitWallIsPricedInPillarBlocks() {
        NavGrid pit = AsciiWorld.of(
                "55555",
                "51115",
                "51115",
                "51115",
                "55555").bounded();
        Path path = Pathfinder.find(pit, PathRequest.of(2, 1, 2, 4, 5, 2, TestBodies.BIPED.building(1)));
        assertFalse(path.reachedGoal());
        assertEquals(3, path.blocksNeeded());
    }
}
