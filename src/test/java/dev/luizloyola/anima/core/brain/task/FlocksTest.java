package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drop clustering: two piles are two flocks; they walk to the nearer pile's middle. */
class FlocksTest {

    /** A drop occupying one cell, the way most of them sit. */
    private static Drop at(int x, int y, int z) {
        Pos cell = new Pos(x, y, z);
        return new Drop(cell, "minecraft:oak_log", Region.of(cell));
    }

    /**
     * A drop that settled on a boundary, so its box overhangs into the next column east — the
     * shape that broke this: the cell it names holds nothing, the cell it leans on holds it up.
     */
    private static Drop straddling(int x, int y, int z) {
        return new Drop(new Pos(x, y, z),
                "minecraft:oak_log", new Region(new Pos(x, y, z), new Pos(x + 1, y, z)));
    }

    @Test
    void separatePilesAreSeparateFlocks() {
        List<Pos> drops = List.of(
                new Pos(0, 64, 0), new Pos(1, 64, 1), new Pos(2, 64, 0),   // pile A
                new Pos(10, 64, 10), new Pos(11, 64, 10));                  // pile B

        assertEquals(2, Flocks.count(drops));
        assertEquals(new Pos(1, 64, 0), Flocks.nearestCentroid(drops, new Pos(-2, 64, 0)),
                "pile A's centroid, since they stand west of it");
        assertEquals(new Pos(11, 64, 10), Flocks.nearestCentroid(drops, new Pos(14, 64, 10)),
                "pile B's centroid from the east");
    }

    @Test
    void noDropsNoFlocks() {
        assertEquals(0, Flocks.count(List.of()));
        assertNull(Flocks.nearestCentroid(List.of(), new Pos(0, 64, 0)));
    }

    @Test
    void aDropOnTheGroundIsGatherable() {
        FakeContext ctx = new FakeContext();
        // FakeProbe's floor: everything at or below GROUND_Y is solid.
        assertTrue(Flocks.gatherable(at(0, FakeProbe.GROUND_Y + 1, 0), ctx));
    }

    @Test
    void aDropOnTheCanopyIsNot() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.set(0, 70, 0, BlockKind.LEAVES);
        assertFalse(Flocks.gatherable(at(0, 71, 0), ctx),
                "leaves hold it out of reach — the body would wait for a decay that may never come");
    }

    /**
     * The fifty-lumberjack case: the log's centre rounds into the empty column, so a single-cell
     * read finds air and calls it fetchable — the leaf carrying it is next door, inside the box.
     */
    @Test
    void aDropLeaningOnALeafFromTheNextColumnIsNot() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.set(1, 70, 0, BlockKind.LEAVES); // the leaf is east of the named cell
        ctx.percepts.blocks.set(0, 70, 0, BlockKind.AIR);    // under the cell the drop names: nothing

        assertTrue(Flocks.gatherable(at(0, 71, 0), ctx),
                "reading only the named cell, it looks like it is sitting on air and fetchable");
        assertFalse(Flocks.gatherable(straddling(0, 71, 0), ctx),
                "its box overhangs the leaf, which is what is actually holding it up");
    }

    @Test
    void aFootprintOverLeavesAndGroundIsGatherable() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.set(0, 70, 0, BlockKind.LEAVES);
        ctx.percepts.blocks.set(1, 70, 0, BlockKind.OTHER); // solid ground under the other half

        assertTrue(Flocks.gatherable(straddling(0, 71, 0), ctx),
                "half of it rests on something solid, so the body can walk to that half");
    }

    @Test
    void anUnseenFootprintStaysGatherable() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.blocks.markUnloaded(0, 0);

        assertTrue(Flocks.gatherable(at(0, 71, 0), ctx),
                "UNKNOWN is not evidence of a canopy — pruning on it would hide real work");
    }
}
