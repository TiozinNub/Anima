package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link Surface}: a column's level is its own top, or the rim of the ravine it lies in. */
class SurfaceTest {

    /** Tops along x only, the same down every z — a cross-section drawn as a row of heights. */
    private static Surface.Tops section(int... heights) {
        return (x, z) -> x < 0 || x >= heights.length ? Surface.UNKNOWN : heights[x];
    }

    @Test
    void flatGroundIsItsOwnLevel() {
        assertEquals(64, Surface.level((x, z) -> 64, 3, 3));
    }

    @Test
    void aSlopeIsItsOwnLevel() {
        // One side high, the other low: nothing to be down in.
        Surface.Tops slope = section(60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70);
        assertEquals(65, Surface.level(slope, 5, 0));
    }

    @Test
    void aRavineFloorLiesUnderItsRim() {
        Surface.Tops ravine = section(70, 70, 70, 70, 40, 40, 40, 70, 70, 70, 70);
        assertEquals(70, Surface.level(ravine, 5, 0));
        assertEquals(30, Surface.level(ravine, 5, 0) - 40, "thirty under the surface");
    }

    @Test
    void aRimFurtherThanTheReachIsAValleyNotARavine() {
        int[] heights = new int[2 * Surface.RIM_REACH + 3];
        java.util.Arrays.fill(heights, 50);
        heights[0] = 80;
        heights[heights.length - 1] = 80;
        int middle = heights.length / 2;
        assertEquals(50, Surface.level(section(heights), middle, 0));
    }

    @Test
    void anUnknownColumnHasNoLevel() {
        assertEquals(Surface.UNKNOWN, Surface.level(section(70, 70), 5, 0));
    }

    @Test
    void theBoxAgreesWithTheColumn() {
        int reach = Surface.RIM_REACH;
        int width = 5;
        int depth = 4;
        int span = width + 2 * reach;
        int rows = depth + 2 * reach;
        int[] tops = new int[span * rows];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < tops.length; i++) {
            tops[i] = random.nextInt(9) == 0 ? Surface.UNKNOWN : 40 + random.nextInt(30);
        }
        Surface.Tops read = (x, z) -> x < 0 || z < 0 || x >= span || z >= rows
                ? Surface.UNKNOWN : tops[z * span + x];
        int[] levels = Surface.levels(tops, width, depth);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                assertEquals(Surface.level(read, x + reach, z + reach), levels[z * width + x]);
            }
        }
    }
}
