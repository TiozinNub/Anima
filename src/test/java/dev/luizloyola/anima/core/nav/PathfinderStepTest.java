package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Floors that stop inside their own cell — {@link CellType#STEP}: slabs, stairs, snow layers,
 * carpets, dirt paths. These all used to classify as obstacles, which made a village street and
 * a wheat field walls to a Person.
 *
 * <p>Drawn worlds rather than a capture: these are claims about the <em>search</em>, so the map
 * is the specification. Whether a real {@code dirt_path} classifies this way is the classifier's
 * question, asked in the gauntlet capture.
 *
 * <p>Several tests come in pairs, because a lone assertion that a path exists does not say what
 * made it exist.
 */
class PathfinderStepTest {

    private static final MoveCapabilities BODY = TestBodies.BIPED; // 2 cells, jumps 1, drops 3

    private static Path find(NavGrid world, int sx, int sy, int sz, int gx, int gy, int gz) {
        return Pathfinder.find(world, PathRequest.of(sx, sy, sz, gx, gy, gz, BODY));
    }

    /** A bottom slab, half a block up. */
    private static final double SLAB = 0.5;
    /** A carpet: one sixteenth, the thinnest floor there is. */
    private static final double CARPET = 0.0625;
    /** A dirt path or farmland: fifteen sixteenths, all but flush with a full block. */
    private static final double PATH = 0.9375;

    @Test
    void aSlabInTheLaneIsWalkedThroughRatherThanRoutedAround() {
        AsciiWorld withSlab = AsciiWorld.of("11111").step(2, 1, 0, 2, 1, 0, SLAB);
        AsciiWorld asAWall = AsciiWorld.of("11111").fill(2, 1, 0, 2, 1, 0, CellType.OBSTACLE);

        assertTrue(find(withSlab, 0, 1, 0, 4, 1, 0).reachedGoal(),
                "a slab is footing to step onto, not a wall");
        assertFalse(find(asAWall, 0, 1, 0, 4, 1, 0).reachedGoal(),
                "the same cell as an obstacle must still block — otherwise this proves nothing");
    }

    @Test
    void standingOnAPartialFloorRecordsHowHighItIs() {
        // The feet-cell is the slab's own cell, and the waypoint carries where inside it the feet
        // came to rest — which is what lets the follower tell "on the slab" from "a block above".
        AsciiWorld world = AsciiWorld.of("111").step(1, 1, 0, 1, 1, 0, SLAB);
        Path path = find(world, 0, 1, 0, 1, 1, 0);

        assertEquals(java.util.List.of(new Waypoint(1, 1, 0, MoveType.WALK, 8)), path.waypoints());
        assertEquals(1.5, path.last().feetY(), 1.0e-9);
    }

    @Test
    void aCarpetIsFootingTooEvenAtOneSixteenth() {
        AsciiWorld world = AsciiWorld.of("111").step(1, 1, 0, 1, 1, 0, CARPET);
        Path path = find(world, 0, 1, 0, 1, 1, 0);

        assertTrue(path.reachedGoal());
        assertEquals(1, path.last().surface16(), "a carpet is one sixteenth of a block");
    }

    @Test
    void aDirtPathIsAllButFlushAndStillItsOwnCell() {
        AsciiWorld world = AsciiWorld.of("111").step(1, 1, 0, 1, 1, 0, PATH);
        Path path = find(world, 0, 1, 0, 1, 1, 0);

        assertTrue(path.reachedGoal());
        assertEquals(15, path.last().surface16());
    }

    @Test
    void aHalfStepRampIsWalkedRatherThanHopped() {
        // +0.5 four times: slab, full block, block-with-slab. Every rise is under the step height,
        // so this is a walk from end to end — the staircase-as-hopscotch reading is the bug.
        AsciiWorld world = AsciiWorld.of("1122")
                .step(1, 1, 0, 1, 1, 0, SLAB)   // feet 1.5
                .step(3, 2, 0, 3, 2, 0, SLAB);  // feet 2.5
        Path path = find(world, 0, 1, 0, 3, 2, 0);

        assertTrue(path.reachedGoal(), "a half-step ramp is climbable");
        assertTrue(path.waypoints().stream().allMatch(w -> w.move() == MoveType.WALK),
                "half a block is a step, not a jump: " + path.waypoints());
        assertEquals(2.5, path.last().feetY(), 1.0e-9);
    }

    @Test
    void aBlockToppedWithASlabIsTooHighToJumpButOneToppedWithACarpetIsNot() {
        // The jump ceiling is a real number, not a cell count — both of these are "a block plus
        // something".
        AsciiWorld slabbed = AsciiWorld.of("12").step(1, 2, 0, 1, 2, 0, SLAB);
        AsciiWorld carpeted = AsciiWorld.of("12").step(1, 2, 0, 1, 2, 0, CARPET);

        assertFalse(find(slabbed, 0, 1, 0, 1, 2, 0).reachedGoal(),
                "a block with a slab on it is 1.5 up — higher than a jump goes");
        assertTrue(find(carpeted, 0, 1, 0, 1, 2, 0).reachedGoal(),
                "a block with a carpet on it is 1.0625 up — a jump clears that");
    }

    @Test
    void aFallIsMeasuredFromTheFeetNotFromTheCell() {
        // maxDrop is 3, and the half block a raised takeoff adds is a real half block: 3.5 fails.
        AsciiWorld flat = AsciiWorld.of("52");
        AsciiWorld fromASlab = AsciiWorld.of("52").step(0, 5, 0, 0, 5, 0, SLAB);

        assertTrue(find(flat, 0, 5, 0, 1, 2, 0).reachedGoal(), "3 blocks is within maxDrop");
        assertFalse(find(fromASlab, 0, 5, 0, 1, 2, 0).reachedGoal(),
                "the same drop taken from half a block higher is 3.5, past what this body accepts");
    }

    @Test
    void aBodyOnAPartialFloorNeedsTheHeadroomItsRaisedFeetAskFor() {
        // The documented conservatism, pinned: the body is modelled as its whole-cell height, so a
        // raised surface charges for a cell of headroom it may not use. A slab under a ceiling two
        // cells up is refused; the same cell without the slab is not. See Pathfinder.fits.
        AsciiWorld slabbed = AsciiWorld.of("111")
                .step(1, 1, 0, 1, 1, 0, SLAB)
                .fill(1, 3, 0, 1, 3, 0, CellType.GROUND);
        AsciiWorld plain = AsciiWorld.of("111").fill(1, 3, 0, 1, 3, 0, CellType.GROUND);

        assertFalse(find(slabbed, 0, 1, 0, 2, 1, 0).reachedGoal(),
                "raised feet need the cell above the one a flat-footed body needs");
        assertTrue(find(plain, 0, 1, 0, 2, 1, 0).reachedGoal(),
                "the same ceiling over an unraised floor is fine");
    }

    @Test
    void aPartialFloorIsSomethingToLandOnNotAChasmToCreepPast() {
        // The follower slows to a crawl beside anything it could fall into. The drop-scan has to
        // stop at a partial floor the same way it stops at a full one, or a slab at the bottom of
        // a shallow pit reads as bottomless and every route past it becomes a careful crossing.
        AsciiWorld ontoASlab = AsciiWorld.of("1 1").step(1, 0, 0, 1, 0, 0, SLAB);
        AsciiWorld bottomless = AsciiWorld.of("1 1");

        assertFalse(NavGrids.isNearDeepDrop(ontoASlab, BODY.maxDrop(), 0, 1, 0),
                "a slab one cell down is a landing — a step off, not a fall");
        assertTrue(NavGrids.isNearDeepDrop(bottomless, BODY.maxDrop(), 0, 1, 0),
                "the same cell with nothing in it is a chasm — otherwise this proves nothing");
    }

    @Test
    void aStepCellAndTheCellAboveItAreNeverTwoPlacesToStand() {
        // One standing place, one node. If the cell above a slab also afforded footing, the search
        // would carry two nodes for one spot, with two costs and two parents.
        AsciiWorld world = AsciiWorld.of("111").step(1, 1, 0, 1, 1, 0, SLAB);

        assertFalse(find(world, 0, 1, 0, 1, 2, 0).reachedGoal(),
                "the cell above a slab is where the body's chest is, not where its feet go");
    }
}
