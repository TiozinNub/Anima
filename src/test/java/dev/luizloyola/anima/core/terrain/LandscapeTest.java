package dev.luizloyola.anima.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.terrain.Landscape.Fluid;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Distances run from a square's closest column, a pond is not a lake, water and lava are told
 * apart, and the flat ground beside a footprint leaves the footprint out.
 */
class LandscapeTest {

    private static final int LEVEL = 64;

    private static GroundSample level(int size) {
        GroundSample sample = new GroundSample(0, 0, size, size);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                sample.set(x, z, LEVEL, 0);
            }
        }
        return sample;
    }

    private static void pool(GroundSample sample, int x0, int z0, int x1, int z1, int flags) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                sample.set(x, z, LEVEL - 1, flags);
            }
        }
    }

    private static Landscape landscape(GroundSample sample) {
        return new Landscape(Terrain.analyse(sample, TerrainRules.configured()));
    }

    @BeforeEach
    @AfterEach
    void defaults() {
        Config.reset();
    }

    @Test
    void distanceRunsFromTheSquaresClosestColumn() {
        GroundSample sample = level(81);
        pool(sample, 60, 0, 80, 80, GroundSample.FLUID); // a lake along the east edge
        Landscape land = landscape(sample);

        // A 17-square centred on x 40 reaches x 48; the lake starts at 60.
        assertEquals(12, land.toFluid(Fluid.WATER, 1, 17, 40, 40), 1e-6);
        assertEquals(20, land.toFluid(Fluid.WATER, 1, 1, 40, 40), 1e-6);
        assertEquals(0, land.toFluid(Fluid.WATER, 1, 17, 55, 40), 1e-6);
    }

    @Test
    void aPondIsNotALake() {
        GroundSample sample = level(81);
        pool(sample, 30, 30, 34, 34, GroundSample.FLUID); // 25 columns
        Landscape land = landscape(sample);

        assertEquals(26, land.toFluid(Fluid.WATER, 1, 1, 30, 4), 1e-6);
        assertEquals(Double.POSITIVE_INFINITY, land.toFluid(Fluid.WATER, 26, 1, 30, 4));
        assertEquals(26, land.toFluid(Fluid.WATER, 25, 1, 30, 4), 1e-6);
    }

    @Test
    void waterAndLavaAreToldApart() {
        GroundSample sample = level(81);
        pool(sample, 10, 10, 12, 12, GroundSample.FLUID | GroundSample.LAVA);
        pool(sample, 70, 70, 72, 72, GroundSample.FLUID);
        Landscape land = landscape(sample);

        assertEquals(0, land.toFluid(Fluid.LAVA, 1, 1, 11, 11), 1e-6);
        assertEquals(Math.hypot(58, 58), land.toFluid(Fluid.LAVA, 1, 1, 70, 70), 1e-4);
        assertEquals(0, land.toFluid(Fluid.WATER, 1, 1, 71, 71), 1e-6);
        assertEquals(Math.hypot(58, 58), land.toFluid(Fluid.WATER, 1, 1, 12, 12), 1e-4);
    }

    @Test
    void distancesMatchAFullSearch() {
        Random random = new Random(20260930);
        int size = 57;
        GroundSample sample = level(size);
        boolean[][] water = new boolean[size][size];
        for (int k = 0; k < 40; k++) {
            int x = random.nextInt(size);
            int z = random.nextInt(size);
            sample.set(x, z, LEVEL - 1, GroundSample.FLUID);
            water[x][z] = true;
        }
        Landscape land = landscape(sample);
        for (int square : new int[] {1, 5, 17}) {
            int h = square / 2;
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    double best = Double.POSITIVE_INFINITY;
                    for (int wx = 0; wx < size; wx++) {
                        for (int wz = 0; wz < size; wz++) {
                            if (water[wx][wz]) {
                                // From the square's closest column, clipped to the read.
                                int cx = Math.max(Math.max(0, x - h), Math.min(Math.min(size - 1, x + h), wx));
                                int cz = Math.max(Math.max(0, z - h), Math.min(Math.min(size - 1, z + h), wz));
                                best = Math.min(best, Math.hypot(wx - cx, wz - cz));
                            }
                        }
                    }
                    assertEquals(best, land.toFluid(Fluid.WATER, 1, square, x, z), 1e-4,
                            "square " + square + " at " + x + ", " + z);
                }
            }
        }
    }

    @Test
    void noBodyInTheReadIsInfinitelyFar() {
        assertEquals(Double.POSITIVE_INFINITY,
                landscape(level(41)).toFluid(Fluid.WATER, 1, 17, 20, 20));
    }

    @Test
    void theGroundBesideAFootprintLeavesTheFootprintOut() {
        GroundSample sample = level(81);
        pool(sample, 0, 0, 80, 20, GroundSample.FLUID); // the north quarter is water
        Landscape land = landscape(sample);

        int around = land.flatAround(40, 60, 16, 17);
        assertEquals(33 * 33 - 17 * 17, around, "all of it flat, the footprint left out");
        assertTrue(land.flatAround(40, 30, 16, 17) < around, "water is not flat ground");
    }

    @Test
    void anyMarkedColumnsServeAsWell() {
        Landscape land = landscape(level(81));

        assertEquals(12, land.toMarked("post", (x, z) -> x == 60 && z == 40, 17, 40, 40), 1e-6);
        assertEquals(Double.POSITIVE_INFINITY, land.toMarked("none", (x, z) -> false, 17, 40, 40));
    }
}
