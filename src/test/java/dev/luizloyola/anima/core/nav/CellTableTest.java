package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The search's own hash tables. Hand-rolled containers earn a test the library ones do not: this
 * is the one place a silent lookup miss would not crash but would quietly make the pathfinder
 * re-compute, re-open a closed cell, or forget a cell it had reached.
 */
class CellTableTest {

    /** The keys the search really uses, so the hash is exercised on the shape it will meet. */
    private static long cell(int x, int y, int z) {
        return Pathfinder.pack(x, y, z);
    }

    @Test
    void theOriginIsAnOrdinaryCellAndNotAnEmptySlot() {
        // pack(0,0,0) is 0L. A table that used the KEY as its emptiness sentinel would lose it,
        // and losing the origin means losing the cell a search starts from.
        CellTable.Nodes nodes = new CellTable.Nodes(16);
        Pathfinder.Node origin = new Pathfinder.Node();
        assertEquals(0L, cell(0, 0, 0), "the packing this test is about");
        assertNull(nodes.get(0L), "nothing stored yet");
        nodes.put(0L, origin);
        assertEquals(origin, nodes.get(0L));
        assertEquals(1, nodes.size());

        CellTable.Flags flags = new CellTable.Flags(16);
        assertEquals(CellTable.Flags.UNKNOWN, flags.get(0L));
        flags.put(0L, false);
        assertEquals(CellTable.Flags.FALSE, flags.get(0L), "false is stored, not forgotten");
    }

    @Test
    void unknownFalseAndTrueAreThreeDifferentAnswers() {
        CellTable.Flags flags = new CellTable.Flags(16);
        long k = cell(4, 70, -9);
        assertEquals(CellTable.Flags.UNKNOWN, flags.get(k));
        flags.put(k, false);
        assertEquals(CellTable.Flags.FALSE, flags.get(k));
        flags.put(k, true);
        assertEquals(CellTable.Flags.TRUE, flags.get(k), "overwrite in place, no second entry");
    }

    @Test
    void survivesGrowingWellPastItsInitialCapacity() {
        // Starts at 16 slots and takes thousands, so every entry is rehashed several times.
        CellTable.Nodes nodes = new CellTable.Nodes(16);
        Map<Long, Pathfinder.Node> mirror = new HashMap<>();
        Random rng = new Random(3);
        for (int i = 0; i < 5000; i++) {
            long k = cell(rng.nextInt(400) - 200, rng.nextInt(120), rng.nextInt(400) - 200);
            Pathfinder.Node n = new Pathfinder.Node();
            n.g = i;
            nodes.put(k, n);
            mirror.put(k, n);
        }
        assertEquals(mirror.size(), nodes.size(), "one slot per distinct cell");
        for (Map.Entry<Long, Pathfinder.Node> e : mirror.entrySet()) {
            assertEquals(e.getValue(), nodes.get(e.getKey()), "cell " + e.getKey());
        }
    }

    @Test
    void slotScanVisitsEveryStoredCellExactlyOnce() {
        // How closedCells() and sealedIn() read the table back out; a slot missed there is a cell
        // missing from a confinement proof.
        CellTable.Nodes nodes = new CellTable.Nodes(16);
        Set<Long> put = new HashSet<>();
        for (int x = -30; x <= 30; x += 3) {
            for (int z = -30; z <= 30; z += 3) {
                long k = cell(x, 64, z);
                nodes.put(k, new Pathfinder.Node());
                put.add(k);
            }
        }
        Set<Long> seen = new HashSet<>();
        for (int slot = 0; slot < nodes.capacity(); slot++) {
            if (nodes.valueAt(slot) != null) {
                assertTrue(seen.add(nodes.keyAt(slot)), "slot scan returned a cell twice");
            }
        }
        assertEquals(put, seen);
    }

    @Test
    void neighbouringCellsDoNotAllLandInOneBucket() {
        // The reason the key is mixed before it is masked: packed cells differ in their low bits,
        // so a raw mask would file a whole row of ground in one place.
        CellTable.Nodes nodes = new CellTable.Nodes(4096);
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                nodes.put(cell(x, 64, z), new Pathfinder.Node());
            }
        }
        assertEquals(1600, nodes.size());
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                assertNotNull(nodes.get(cell(x, 64, z)), "a row of ground must stay findable");
            }
        }
    }

    @Test
    void negativeCoordinatesRoundTrip() {
        CellTable.Flags flags = new CellTable.Flags(16);
        long k = cell(-1974, 63, 1276);
        flags.put(k, true);
        assertEquals(CellTable.Flags.TRUE, flags.get(k));
        assertEquals(CellTable.Flags.UNKNOWN, flags.get(cell(-1974, 63, 1277)), "no false hit");
    }
}
