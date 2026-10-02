package dev.luizloyola.anima.core.nav;

/**
 * The surface: how high a body stands on a column when it is out in the open, and so how far under
 * it a feet cell lies. Luiz, 2026-10-02: a wander does not go below the surface into caves or
 * ravines; an errand goes only when its work is down there.
 *
 * <p>A column's <b>top</b> is the feet height over its top natural block ({@link NavGrid#naturalTop}):
 * a cave's floor lies under the hill's top, a house's floor does not lie under its roof. A ravine is
 * open to the sky, so its own top is its floor; what makes it one is the rim, both sides of it,
 * close by. So the <b>level</b> of a column is the higher of its top and its rim: along each of four
 * lines through it, the lower of the highest tops within {@link #RIM_REACH} on either side. One
 * side high and the other low is a slope, and reads as nothing.
 *
 * <p>Read off two stretches of the forest (2026-10-02): more than {@link #TOLERANCE} under the
 * level is 2.3% and 0.4% of their columns, and those are its ravines, sinkholes and pits.
 */
public final class Surface {

    private Surface() {
    }

    /** No opinion: an unloaded column, nothing natural under it, a grid that cannot say. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** How far a rim is looked for either side. Ravines run to about twice this wide. */
    public static final int RIM_REACH = 12;

    /** How far under the level a feet cell may lie and still be out on the surface. */
    public static final int TOLERANCE = 6;

    /** The four lines a rim is read along, one direction of each. */
    private static final int[][] LINES = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    /** The tops of a set of columns, by world coordinates — {@link #UNKNOWN} where unread. */
    @FunctionalInterface
    public interface Tops {
        int at(int x, int z);
    }

    /** This column's level from the tops around it, or {@link #UNKNOWN} when its own is unknown. */
    public static int level(Tops tops, int x, int z) {
        int top = tops.at(x, z);
        if (top == UNKNOWN) {
            return UNKNOWN;
        }
        int rim = UNKNOWN;
        for (int[] line : LINES) {
            int ahead = UNKNOWN;
            int behind = UNKNOWN;
            for (int d = 1; d <= RIM_REACH; d++) {
                ahead = Math.max(ahead, tops.at(x + d * line[0], z + d * line[1]));
                behind = Math.max(behind, tops.at(x - d * line[0], z - d * line[1]));
            }
            rim = Math.max(rim, Math.min(ahead, behind));
        }
        return Math.max(top, rim);
    }

    /**
     * {@link #level} for every column of a box at once, from the tops of the box widened by
     * {@link #RIM_REACH} each side, row by row in x: {@code tops} is
     * {@code (width + 2R) × (depth + 2R)}, the answer {@code width × depth}.
     */
    public static int[] levels(int[] tops, int width, int depth) {
        int span = width + 2 * RIM_REACH;
        int[] out = new int[width * depth];
        Tops read = (x, z) -> x < 0 || z < 0 || x >= span || z >= depth + 2 * RIM_REACH
                ? UNKNOWN : tops[z * span + x];
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                out[z * width + x] = level(read, x + RIM_REACH, z + RIM_REACH);
            }
        }
        return out;
    }

    /** How far under the surface feet cell {@code (x,y,z)} lies; 0 or less on it, and when unknown. */
    public static int depth(NavGrid grid, int x, int y, int z) {
        int level = grid.surfaceLevel(x, z);
        return level == UNKNOWN ? 0 : level - y;
    }

    /** Whether feet cell {@code (x,y,z)} lies further under the surface than {@link #TOLERANCE}. */
    public static boolean under(NavGrid grid, int x, int y, int z) {
        return depth(grid, x, y, z) > TOLERANCE;
    }
}
