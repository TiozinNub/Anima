package dev.luizloyola.anima.core.terrain;

import java.util.function.IntFunction;

/**
 * Whether a column that shows ice or snow at its top is frozen water — sea ice, a lake's sheet, an
 * iceberg — and where that water's surface is.
 *
 * <p>Ice has no fluid state, so a reader that asks only the surface block takes a frozen sea for
 * flat land: in a large-biomes world with snowy country a quarter of the surface was ice, and the
 * HOME search put half its homes on it (2026-09-25). Ice over water is water; so is ice that rises
 * through the sea's surface, which is an iceberg's keel whether it floats or stands on the floor.
 * Snow on top — an iceberg's cap — is walked through. Ice on land (a spike, a road of blue ice) ends
 * on something else and stays land.
 */
public final class FrozenWater {

    /** What a cell of the column is, as far as this question goes. */
    public enum Cell {
        /** {@code #minecraft:ice}: ice, packed, blue, frosted. */
        ICE,
        /** {@code #minecraft:snow}: a snow layer, a snow block, powder snow. */
        SNOW,
        /** Water, or a block holding it. */
        WATER,
        OTHER
    }

    /** The answer for a column that is not frozen water. */
    public static final int LAND = Integer.MIN_VALUE;

    private FrozenWater() {
    }

    /**
     * The y of the water's surface under the ice at {@code top}, or {@link #LAND}.
     *
     * @param cellAt   what the column holds at a y
     * @param top      the column's top block, ice or snow
     * @param seaLevel the world's sea level; its surface block is one below it
     * @param maxDepth how far down to look before calling it land
     */
    public static int surface(IntFunction<Cell> cellAt, int top, int seaLevel, int maxDepth) {
        int waterline = seaLevel - 1;
        int firstIce = LAND;
        for (int y = top; y >= top - maxDepth; y--) {
            Cell cell = cellAt.apply(y);
            if (cell == Cell.ICE) {
                if (firstIce == LAND) {
                    firstIce = y;
                }
                // Through the sea's surface: a keel, so this is the sea. Only ice that starts at or
                // above the waterline — ice at the top of a pit below sea level is not an iceberg.
                if (y < waterline && firstIce >= waterline) {
                    return waterline;
                }
            } else if (firstIce != LAND || cell != Cell.SNOW) {
                return firstIce != LAND && cell == Cell.WATER ? firstIce : LAND;
            }
        }
        return LAND;
    }
}
