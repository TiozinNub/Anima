package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.AsciiWorld;
import dev.luizloyola.anima.core.nav.CellNeed;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.NavGrids;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hand-drawn worlds rather than captures: these are claims about the <em>policy</em>, so the map is
 * the specification. Whether a real magma block classifies as {@link CellType#DANGER} is the
 * classifier's question, asked elsewhere.
 *
 * <p>Most cases come in pairs. A lone "this cell is refused" says nothing about <em>why</em> it was
 * refused, and every one of these maps has several ways to fail.
 */
class StandingTest {

    /**
     * A Person: 1.8 tall, jumps 1, drops 3, leaps 3, swims — the profile defaults for
     * {@code BODY_HEIGHT} and friends. Written out rather than read from a profile because these
     * expectations were worked out by hand for these numbers.
     */
    private static final MoveCapabilities PERSON = new MoveCapabilities(1.8, 1, 3, 3, true, 36);

    /** A bottom slab: a floor half a block up inside its own cell. */
    private static final double SLAB = 0.5;

    @Test
    void aCellWithNothingUnderItIsNotStandable() {
        AsciiWorld world = AsciiWorld.of("111");

        assertFalse(Standing.standable(world, PERSON, 1, 4, 0), "there is only air below y=4");
        assertTrue(Standing.standable(world, PERSON, 1, 1, 0),
                "the same column at ground level is fine — otherwise this proves nothing");
    }

    @Test
    void aCellBuriedInStoneIsNotStandable() {
        // The uphill half of the complaint: a target rolled at the feet's own y lands inside the
        // hill, and nothing downstream ever lifts it back out.
        AsciiWorld hill = AsciiWorld.of("555");

        assertFalse(Standing.standable(hill, PERSON, 1, 2, 0), "y=2 is three blocks of rock down");
        assertTrue(Standing.standable(hill, PERSON, 1, 5, 0), "the hilltop is where it should land");
    }

    @Test
    void neitherAFloorOfLavaNorLavaAtTheFeetIsStandable() {
        AsciiWorld pool = AsciiWorld.of("1L1");

        assertFalse(Standing.standable(pool, PERSON, 1, 1, 0), "the floor below is lava");
        assertFalse(Standing.standable(pool, PERSON, 1, 0, 0), "and the lava itself is not a spot");
    }

    @Test
    void deepWaterIsNotStandable() {
        AsciiWorld lake = AsciiWorld.of("1WW1");

        assertFalse(Standing.standable(lake, PERSON, 1, 0, 0), "the surface is not a floor");
        assertFalse(Standing.standable(lake, PERSON, 1, -1, 0), "nor is standing on the lakebed");
    }

    /**
     * The case that separates this from the rest of the engine, pinned from both sides so that
     * "fixing" the divergence has to delete an assertion rather than quietly pass.
     *
     * <p>{@code CellNeed.FOOTING} is right to take a waded puddle: an errand crossing a stream is
     * fine. A wander beat is five to fifteen seconds of standing still, and standing still in the
     * stream is not.
     */
    @Test
    @DisplayName("wadeable water is footing to the pathfinder and still not somewhere to stand")
    void wadeableWaterIsFootingButNotAStandingSpot() {
        AsciiWorld puddle = AsciiWorld.of("1w1");

        assertTrue(NavGrids.satisfies(puddle, new CellNeed(1, 0, 0, CellNeed.Need.FOOTING)),
                "one block of water over a bed is footing everywhere else in the engine");
        assertFalse(Standing.standable(puddle, PERSON, 1, 0, 0),
                "and is deliberately refused here: a wander target is dry land");
    }

    @Test
    void aCliffLipIsNotStandable() {
        // The last cell of the plateau, with a bottomless column beside it — a step in any
        // direction is further than this body will fall.
        AsciiWorld plateau = AsciiWorld.of("111 ");

        assertFalse(Standing.standable(plateau, PERSON, 2, 1, 0), "x=2 is the lip");
        assertTrue(Standing.standable(plateau, PERSON, 1, 1, 0), "one cell back from it is fine");
    }

    @Test
    @DisplayName("a slab is footing, and its raised feet cost a cell of headroom")
    void aSlabIsStandableAndSpendsAnExtraCellAboveIt() {
        AsciiWorld open = AsciiWorld.of("111").step(1, 1, 0, 1, 1, 0, SLAB);
        // A lid two cells above the floor: a 1.8 body standing flat fits under it, the same body
        // standing half a block higher does not.
        AsciiWorld slabbedUnderALid = AsciiWorld.of("111")
                .step(1, 1, 0, 1, 1, 0, SLAB)
                .fill(1, 3, 0, 1, 3, 0, CellType.GROUND);
        AsciiWorld flatUnderTheSameLid = AsciiWorld.of("111")
                .fill(1, 3, 0, 1, 3, 0, CellType.GROUND);

        assertTrue(Standing.standable(open, PERSON, 1, 1, 0), "a slab is a floor, not a wall");
        assertFalse(Standing.standable(slabbedUnderALid, PERSON, 1, 1, 0),
                "0.5 + 1.8 puts the head through a ceiling two cells up");
        assertTrue(Standing.standable(flatUnderTheSameLid, PERSON, 1, 1, 0),
                "the identical ceiling over a flat floor is headroom enough");
    }

    @Test
    void aColumnOutsideTheGridIsNotStandable() {
        assertFalse(Standing.standable(AsciiWorld.of("111"), PERSON, 9, 1, 0),
                "off the drawn map, which reads OBSTACLE per the NavGrid contract");
        assertFalse(Standing.standable(NavGrid.UNKNOWN, PERSON, 0, 0, 0),
                "and a rig with no terrain sense finds nowhere at all");
    }

    /**
     * The bounds rule on its own. {@link AsciiWorld} cannot pose this: it never overrides
     * {@code inBounds}, so every cell it draws is in bounds and every cell it does not is already
     * {@link CellType#OBSTACLE}. This grid answers perfect ground for a column it admits it has no
     * data for — a contract violation on purpose, so that deleting the {@code inBounds} check fails
     * exactly one test.
     */
    @Test
    @DisplayName("a column the grid has no data for is refused even when it reads like ground")
    void anUnloadedColumnIsNotStandable() {
        NavGrid flatWithAHole = new NavGrid() {
            @Override
            public CellType cell(int x, int y, int z) {
                return y < 1 ? CellType.GROUND : CellType.PASSABLE;
            }

            @Override
            public boolean inBounds(int x, int y, int z) {
                return x != 4;
            }
        };

        assertTrue(Standing.standable(flatWithAHole, PERSON, 3, 1, 0));
        assertFalse(Standing.standable(flatWithAHole, PERSON, 4, 1, 0),
                "unloaded is not 'probably fine'");
    }

    /**
     * A ledge one cell below the feet and a slab one cell above them, with the feet's own cell
     * standable at neither. Both candidates are one step away, so only the tie-break decides.
     */
    @Test
    @DisplayName("spot takes the nearest standing place, and the lower one on a tie")
    void spotPrefersTheNearestAndBreaksTiesDownward() {
        AsciiWorld twoStorey = AsciiWorld.of(
                "444",
                "444",
                "444").step(1, 6, 1, 1, 6, 1, SLAB);

        assertTrue(Standing.standable(twoStorey, PERSON, 1, 4, 1), "the ledge below");
        assertTrue(Standing.standable(twoStorey, PERSON, 1, 6, 1),
                "the slab above — without this the tie is not a tie");
        assertFalse(Standing.standable(twoStorey, PERSON, 1, 5, 1), "and nothing between them");

        assertEquals(Optional.of(new Pos(1, 4, 1)),
                Standing.spot(twoStorey, PERSON, 1, 1, 5, 2),
                "a drop is cheaper than a climb");
    }

    @Test
    void spotFindsNothingWhenTheWholeWindowIsSolid() {
        AsciiWorld mountain = AsciiWorld.of("999");

        assertEquals(Optional.empty(), Standing.spot(mountain, PERSON, 1, 0, 4, 3),
                "y=1..7 is all rock, and there is no fallback to invent one");
        // The window is the caller's, not the terrain's: the same column with a wider reach comes
        // up out of the mountain.
        assertEquals(Optional.of(new Pos(1, 9, 0)), Standing.spot(mountain, PERSON, 1, 0, 4, 5));
    }
}
