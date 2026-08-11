package dev.luizloyola.anima.core.nav;

/**
 * Pure helpers over a {@link NavGrid}, shared by the engine and the (mod-layer) follower.
 */
public final class NavGrids {
    private NavGrids() {}

    /**
     * Whether a misstep out of feet-cell {@code (x,y,z)} could be catastrophic: a cardinal
     * neighbour open at this level with no floor within {@code maxDrop} below (a chasm), or a
     * floor that is harmful (lava) or water too deep to stand up in. The follower slows and steers
     * tighter while it holds; drops within {@code maxDrop} onto solid ground do not
     * count.
     *
     * <p><b>A puddle is not a hazard.</b> Water with a bed directly under it is waded, not fallen
     * into. Judged by the bed alone, not by whether this body would fit — the helper is given no
     * body, and one block of water over stone is not a pool.
     */
    public static boolean isNearDeepDrop(NavGrid grid, int maxDrop, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0);
            int nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
            if (grid.cell(nx, y, nz) != CellType.PASSABLE) {
                // A wall, not a step-off — and a partial floor (a slab, a carpet) is footing
                // rather than a hole.
                continue;
            }
            int floor = y - 1;
            int limit = y - maxDrop - 1;
            while (floor >= limit && grid.cell(nx, floor, nz) == CellType.PASSABLE) {
                floor--;
            }
            if (floor < limit) {
                return true; // open all the way past survivable depth
            }
            CellType landing = grid.cell(nx, floor, nz);
            if (landing == CellType.DANGER) {
                return true;
            }
            if (landing == CellType.WATER && grid.cell(nx, floor - 1, nz) != CellType.GROUND) {
                return true; // deep enough that stepping in is swimming, not wading
            }
        }
        return false;
    }
}
