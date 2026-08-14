package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The completion-critical cells {@link PathIntegrity} watches: footing and body clearance along
 * the deck a level edge crosses, a swim waypoint's surface float, a vertical move's destination
 * standability — re-validated a few nodes ahead by {@code Navigator.pathChangedAhead}.
 */
class PathIntegrityTest {

    private static final MoveCapabilities PERSON = TestBodies.BIPED; // 2 cells tall

    @Test
    void unitWalkNeedsFloorGroundAndBodyClearanceAtBothCells() {
        // A one-cell step: the line is [from, to], so both cells' floor+body are watched.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(3, 10, 7, MoveType.WALK), new Waypoint(4, 10, 7, MoveType.WALK), PERSON);
        assertEquals(List.of(
                new CellNeed(3, 10, 7, CellNeed.Need.FOOTING),
                new CellNeed(3, 11, 7, CellNeed.Need.CLEAR),
                new CellNeed(4, 10, 7, CellNeed.Need.FOOTING),
                new CellNeed(4, 11, 7, CellNeed.Need.CLEAR)), needs);
    }

    @Test
    void strideWatchesEveryDeckCellBetweenTheWaypoints() {
        // Every cell a stride crosses is watched, not just the endpoints destination-only
        // watching saw — so a block pulled from a bridge's middle is caught.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(0, 5, 0, MoveType.WALK), new Waypoint(3, 5, 0, MoveType.WALK), PERSON);
        for (int x = 0; x <= 3; x++) {
            assertTrue(needs.contains(new CellNeed(x, 5, 0, CellNeed.Need.FOOTING)),
                    "footing at x=" + x + " must be watched across the stride");
        }
    }

    @Test
    void lineCoversEveryCellCrossedOnAShallowStride() {
        // A (3,1) stride: Bresenham crosses one cell per column — the deck under the feet.
        List<int[]> cells = PathIntegrity.lineCells(0, 0, 3, 1);
        assertEquals(4, cells.size());
        assertEquals(0, cells.get(0)[0]);            
        assertEquals(3, cells.get(cells.size() - 1)[0]); 
        assertTrue(cells.stream().allMatch(c -> c[1] == 0 || c[1] == 1),
                "every crossed cell sits in the z-band the segment spans");
    }

    @Test
    void climbingOutOfWaterDoesNotAskTheSwimWaypointForFooting() {
        // The near end is watched as whatever the body was DOING there: a climb-out starts on the
        // last SWIM waypoint, whose cell is water, and asking it for FOOTING failed integrity on
        // the first tick — nine searches for one ninety-block trip.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(2, 62, 8, MoveType.SWIM), new Waypoint(3, 63, 8, MoveType.WALK),
                PERSON);
        assertTrue(needs.contains(new CellNeed(2, 62, 8, CellNeed.Need.WATER)),
                "the swim end wants to still be in water: " + needs);
        assertTrue(needs.contains(new CellNeed(2, 63, 8, CellNeed.Need.ROOM)),
                "and room for the body over it: " + needs);
        assertFalse(needs.contains(new CellNeed(2, 62, 8, CellNeed.Need.FOOTING)),
                "nothing may ask a swimming body for a floor under its feet: " + needs);
        assertTrue(needs.contains(new CellNeed(3, 63, 8, CellNeed.Need.FOOTING)),
                "the land the walk arrives on still needs footing: " + needs);
    }

    @Test
    void anOrdinaryWalkOutOfADryCellStillWatchesItsOwnStart() {
        // The guard is on the MOVE, not on the water.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(2, 63, 8, MoveType.JUMP), new Waypoint(3, 63, 8, MoveType.WALK),
                PERSON);
        assertTrue(needs.contains(new CellNeed(2, 63, 8, CellNeed.Need.FOOTING)),
                "a dry near end is still watched for footing: " + needs);
    }

    @Test
    void swimEdgeNeedsWaterFeetAndRoomForTheBodyAbove() {
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(1, 63, 8, MoveType.SWIM), new Waypoint(2, 63, 8, MoveType.SWIM), PERSON);
        assertEquals(List.of(
                new CellNeed(2, 63, 8, CellNeed.Need.WATER),
                new CellNeed(2, 64, 8, CellNeed.Need.ROOM)), needs);
    }

    /**
     * A dive is a water move. Asking a submerged cell for a FLOOR — what every non-swim vertical
     * move asks — condemned the plan on the tick it was made: re-plan, identical route, repeat.
     */
    @Test
    void diveAndSurfaceAreWatchedAsWaterAndNeverAskedForAFloor() {
        for (MoveType move : List.of(MoveType.DIVE, MoveType.SURFACE)) {
            List<CellNeed> needs = PathIntegrity.edgeNeeds(
                    new Waypoint(2, 63, 8, MoveType.SWIM), new Waypoint(2, 61, 8, move), PERSON);
            assertEquals(List.of(
                    new CellNeed(2, 61, 8, CellNeed.Need.WATER),
                    new CellNeed(2, 62, 8, CellNeed.Need.ROOM)), needs,
                    move + " must be watched as water, not as a drop onto a floor");
        }
    }

    @Test
    void dropAndJumpWatchTheDestinationOnly() {
        // Drop/jump: only the landing's standability — the shaft/cleared block is v1-out-of-scope.
        for (MoveType move : List.of(MoveType.DROP, MoveType.JUMP)) {
            List<CellNeed> needs = PathIntegrity.edgeNeeds(
                    new Waypoint(0, 8, 0, MoveType.WALK), new Waypoint(2, 5, 0, move), PERSON);
            assertEquals(List.of(
                    new CellNeed(2, 5, 0, CellNeed.Need.FOOTING),
                    new CellNeed(2, 6, 0, CellNeed.Need.CLEAR)), needs,
                    move + " should watch only the landing cell's standability");
        }
    }

    @Test
    void leapWatchesLandingTakeoffHeadroomAndTheWholeArc() {
        // A 2-wide gap: columns x=1,2 at z=0.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(0, 5, 0, MoveType.WALK), new Waypoint(3, 5, 0, MoveType.LEAP), PERSON);
        assertEquals(List.of(
                // landing standability
                new CellNeed(3, 5, 0, CellNeed.Need.FOOTING),
                new CellNeed(3, 6, 0, CellNeed.Need.CLEAR),
                // takeoff headroom (y + height, above the launch cell)
                new CellNeed(0, 7, 0, CellNeed.Need.CLEAR),
                // arc corridor over gap column x=1: body-height+1 cells (y..y+height)
                new CellNeed(1, 5, 0, CellNeed.Need.CLEAR),
                new CellNeed(1, 6, 0, CellNeed.Need.CLEAR),
                new CellNeed(1, 7, 0, CellNeed.Need.CLEAR),
                // arc corridor over gap column x=2
                new CellNeed(2, 5, 0, CellNeed.Need.CLEAR),
                new CellNeed(2, 6, 0, CellNeed.Need.CLEAR),
                new CellNeed(2, 7, 0, CellNeed.Need.CLEAR)), needs);
        // The gap floor itself is cost-only — never watched (filling it doesn't break the leap).
        assertTrue(needs.stream().noneMatch(n -> n.y() == 4 && (n.x() == 1 || n.x() == 2)),
                "gap-column floors must not be watched");
    }

    @Test
    void aLevelRunUpWatchesEveryCellItsFeetCross() {
        // A level run-up is marked ordinary travel: the stride still puts deck cells between its
        // endpoints, and watching only the takeoff would stop watching them.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(0, 5, 0, MoveType.WALK), new Waypoint(3, 5, 0, MoveType.RUNUP), PERSON);
        assertEquals(List.of(
                new CellNeed(0, 5, 0, CellNeed.Need.FOOTING),
                new CellNeed(0, 6, 0, CellNeed.Need.CLEAR),
                new CellNeed(1, 5, 0, CellNeed.Need.FOOTING),
                new CellNeed(1, 6, 0, CellNeed.Need.CLEAR),
                new CellNeed(2, 5, 0, CellNeed.Need.FOOTING),
                new CellNeed(2, 6, 0, CellNeed.Need.CLEAR),
                new CellNeed(3, 5, 0, CellNeed.Need.FOOTING),
                new CellNeed(3, 6, 0, CellNeed.Need.CLEAR)), needs);
    }

    @Test
    void aRisingRunUpWatchesItsTakeoffOnly() {
        // The staircase-summit run-up is a jump, and the line rule would ask the cell BELOW the
        // summit to be standable at the summit's own level — which on a staircase is thin air.
        List<CellNeed> needs = PathIntegrity.edgeNeeds(
                new Waypoint(0, 4, 0, MoveType.WALK), new Waypoint(1, 5, 0, MoveType.RUNUP), PERSON);
        assertEquals(List.of(
                new CellNeed(1, 5, 0, CellNeed.Need.FOOTING),
                new CellNeed(1, 6, 0, CellNeed.Need.CLEAR)), needs);
    }
}
