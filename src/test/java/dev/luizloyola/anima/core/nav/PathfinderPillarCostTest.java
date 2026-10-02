package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What recorded pillars cost a building search. Reports timings rather than asserting them, past
 * an order of magnitude: on the forest, 2026-10-02, the pillar lookup was 21% of the server
 * thread's ticks over 20 ms, mostly a swimmer's searches with 15 recorded blocks nearby.
 */
class PathfinderPillarCostTest {

    private static final int SIZE = 96;
    private static final int PLATEAU = 48;
    private static final int PLATEAU_TOP = 12;
    private static final int PILLAR_TOP = 5;
    /** Where the drawn map sits in the world: forest coordinates, negative like the case's. */
    private static final int OX = -200;
    private static final int OY = 40;
    private static final int OZ = -650;
    private static final MoveCapabilities SCALER = TestBodies.BIPED.withScaling(true);

    /**
     * Ground with feet at y 1, water over it up to {@code waterTop} if any, a plateau too tall to
     * climb in the middle and recorded pillars standing every twelve blocks. Arrays, not
     * {@link AsciiWorld}: its map lookups would swamp what this measures.
     */
    private static final class Field implements NavGrid {
        private final int[] height = new int[SIZE * SIZE];
        private final int waterTop;

        Field(Set<Long> pillars, int waterTop) {
            this.waterTop = waterTop;
            java.util.Arrays.fill(this.height, 1);
            for (int x = PLATEAU - 1; x <= PLATEAU + 1; x++) {
                for (int z = PLATEAU - 1; z <= PLATEAU + 1; z++) {
                    this.height[x * SIZE + z] = PLATEAU_TOP;
                }
            }
            for (long cell : pillars) {
                int x = Pathfinder.unpackX(cell) - OX;
                int z = Pathfinder.unpackZ(cell) - OZ;
                this.height[x * SIZE + z] = Math.max(this.height[x * SIZE + z],
                        Pathfinder.unpackY(cell) - OY + 1);
            }
        }

        @Override
        public CellType cell(int x, int y, int z) {
            x -= OX;
            y -= OY;
            z -= OZ;
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return CellType.OBSTACLE;
            if (y < this.height[x * SIZE + z]) return CellType.GROUND;
            return y <= this.waterTop ? CellType.WATER : CellType.PASSABLE;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            return x >= OX && z >= OZ && x < OX + SIZE && z < OZ + SIZE;
        }
    }

    /** Columns every twelve blocks, clear of the plateau. */
    private static Set<Long> scattered() {
        Set<Long> cells = new HashSet<>();
        for (int x = 3; x < SIZE - 3; x += 12) {
            for (int z = 3; z < SIZE - 3; z += 12) {
                if (Math.abs(x - PLATEAU) <= 4 && Math.abs(z - PLATEAU) <= 4) continue;
                for (int y = 1; y < PILLAR_TOP; y++) {
                    cells.add(LaidBlocks.cell(OX + x, OY + y, OZ + z));
                }
            }
        }
        return cells;
    }

    private static PathRequest toThePlateau(int startY, Set<Long> pillars) {
        return PathRequest.of(OX + PLATEAU - 12, OY + startY, OZ + PLATEAU,
                OX + PLATEAU, OY + PLATEAU_TOP, OZ + PLATEAU, SCALER).near(pillars);
    }

    /** Mean milliseconds per {@link Pathfinder#find}, after a warm-up. */
    private static double timed(NavGrid grid, PathRequest request) {
        for (int i = 0; i < 30; i++) {
            Pathfinder.find(grid, request);
        }
        int runs = 60;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            Pathfinder.find(grid, request);
        }
        return (System.nanoTime() - start) / 1e6 / runs;
    }

    private static void measure(String scene, int waterTop, int startY) {
        Set<Long> many = scattered();
        assertTrue(many.size() > 200, () -> many.size() + " pillar blocks");
        Field field = new Field(many, waterTop);
        Set<Long> one = Set.of(LaidBlocks.cell(OX + 3, OY + 1, OZ + 3));

        Path amongMany = Pathfinder.find(field, toThePlateau(startY, many));
        assertFalse(amongMany.reachedGoal(), "the plateau is out of reach of every pillar");
        assertEquals(amongMany.waypoints(), Pathfinder.find(field, toThePlateau(startY, many)).waypoints());

        double manyMs = timed(field, toThePlateau(startY, many));
        double oneMs = timed(field, toThePlateau(startY, one));
        System.out.printf("pillar cost, %s: %d recorded %.2f ms/find, 1 recorded %.2f ms/find%n",
                scene, many.size(), manyMs, oneMs);
        assertTrue(manyMs < oneMs * 10, () -> manyMs + " ms against " + oneMs + " ms");
    }

    @Test
    void aBuildingSearchOnDryGroundAmongHundredsOfPillars() {
        measure("dry", 0, 1);
    }

    @Test
    void aBuildingSearchAcrossALakeAmongHundredsOfPillars() {
        measure("lake", 5, 5);
    }
}
