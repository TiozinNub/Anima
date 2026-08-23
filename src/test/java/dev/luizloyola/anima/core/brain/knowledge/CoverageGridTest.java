package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

/**
 * The sub-cell union. A cell is covered a 2-block square at a time, because a near field of eight
 * blocks can never contain a whole eight-block cell unless it is almost perfectly centred — a body
 * at a cell's corner is 11.3 blocks from the far corner, three past what it can individuate.
 */
class CoverageGridTest {

    /** Four cells by four, so a body can stand in one and reach into its neighbours. */
    private static CoverageGrid grid() {
        return new CoverageGrid(new Region(new Pos(0, 63, 0), new Pos(31, 70, 31)));
    }

    @Test
    void aFreshGridIsEntirelyUnswept() {
        CoverageGrid grid = grid();
        assertEquals(16, grid.cells(), "four by four");
        assertFalse(grid.allSettled());
        assertEquals(0, grid.settledCount());
        assertEquals(0, grid.mask(0));
    }

    @Test
    void standingInACellCoversMostOfItAndNotItsNeighbours() {
        CoverageGrid grid = grid();
        grid.markNear(new Pos(4, 64, 4), 8); // the centre of cell (0,0)

        assertEquals(CoverageGrid.FULL, grid.mask(grid.cellAt(4, 4)),
                "every square centre in this cell is within 4.25 blocks of the middle");
        assertTrue(grid.confidence(grid.cellAt(12, 4)) < 1.0,
                "the neighbour is only clipped — the centre test called it fully known");
        assertEquals(0, grid.mask(grid.cellAt(28, 28)),
                "and a cell nothing reached is untouched");
    }

    @Test
    void standingAtACellsCornerLeavesItsFarCornerUncovered() {
        CoverageGrid grid = grid();
        grid.markNear(new Pos(0, 64, 0), 8);

        int cell = grid.cellAt(0, 0);
        assertTrue(grid.confidence(cell) < 1.0,
                "the far square centre is 9.9 blocks off — beyond what a near field individuates");
        assertTrue(grid.settled(cell), "thirteen of sixteen is still past ENOUGH");
    }

    @Test
    void twoVisitsUnionRatherThanReplace() {
        CoverageGrid grid = grid();
        // Neither end of the cell settles it alone; between them they cover it.
        grid.markNear(new Pos(1, 64, 4), 3);
        double afterOne = grid.confidence(grid.cellAt(4, 4));
        grid.markNear(new Pos(7, 64, 4), 3);

        assertTrue(grid.confidence(grid.cellAt(4, 4)) > afterOne, "the second visit adds squares");
        assertTrue(grid.settled(grid.cellAt(4, 4)), "and together they clear the line");
    }

    @Test
    void theSameVisitTwiceChangesNothing() {
        CoverageGrid grid = grid();
        assertTrue(grid.markNear(new Pos(4, 64, 4), 8), "the first pass sets bits");
        assertFalse(grid.markNear(new Pos(4, 64, 4), 8), "the second has nothing left to set");
    }

    @Test
    void aSettledCellIsBankedWhole() {
        CoverageGrid grid = grid();
        grid.markFull(new Pos(8, 63, 8));

        assertEquals(CoverageGrid.FULL, grid.mask(grid.cellAt(8, 8)));
        assertTrue(grid.settled(grid.cellAt(8, 8)));
    }

    @Test
    void positionsOutsideTheGridAreIgnoredRatherThanThrowing() {
        CoverageGrid grid = grid();
        assertEquals(-1, grid.cellAt(-1, 0));
        assertFalse(grid.markNear(new Pos(500, 64, 500), 8), "a hauler at the yard is off the box");
        assertFalse(grid.markFull(new Pos(-8, 63, 0)));
    }

    @Test
    void masksInAnswersOnlyTheCellsOverlappingTheAsker() {
        CoverageGrid grid = grid();
        grid.markFull(new Pos(0, 63, 0));
        grid.markFull(new Pos(24, 63, 24));

        var half = grid.masksIn(new Region(new Pos(0, 63, 0), new Pos(15, 70, 15)));
        assertEquals(1, half.size(), "only the corner that falls inside");
        assertEquals(CoverageGrid.FULL, half.get(new Pos(0, 63, 0)));
    }

    @Test
    void aGridEveryCellOfWhichIsSettledIsDone() {
        CoverageGrid grid = grid();
        for (int cell = 0; cell < grid.cells(); cell++) {
            grid.markFull(grid.cornerOf(cell));
        }
        assertTrue(grid.allSettled());
        assertEquals(16, grid.settledCount());
    }
}
