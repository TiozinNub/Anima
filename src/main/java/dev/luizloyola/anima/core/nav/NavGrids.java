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
     *
     * <p><b>Body-blind: deep water counts against everyone here.</b> Right for a caller pricing
     * the ground it is about to cross — a shoreline is worth crossing slowly and steering tight on
     * whether or not the body can swim. A caller asking whether a cell is <em>safe</em> wants
     * {@link #isNearDeepDrop(NavGrid, MoveCapabilities, int, int, int)}, which does not hold an
     * ocean against a swimmer.
     */
    public static boolean isNearDeepDrop(NavGrid grid, int maxDrop, int x, int y, int z) {
        return isNearDeepDrop(grid, maxDrop, true, x, y, z);
    }

    /**
     * As {@link #isNearDeepDrop(NavGrid, int, int, int, int)} but asked of a body — <b>the form a
     * new caller wants</b>, because the question is what could kill this body and only the body
     * answers that. Chasms and lava are unchanged; deep water is a hazard only to a body that
     * cannot swim.
     *
     * <p>The puddle rule carried to its end: water is a hazard when stepping in is worse than
     * standing in it, and to a swimmer an ocean is what one block over stone is to everybody.
     * Held against swimmers too, it refused {@code Standing} every dry cell on a shoreline and
     * every plank of a dock — the neighbour scan stops on the water and never finds a bed.
     */
    public static boolean isNearDeepDrop(NavGrid grid, MoveCapabilities body, int x, int y, int z) {
        return isNearDeepDrop(grid, body.maxDrop(), !body.canSwim(), x, y, z);
    }

    private static boolean isNearDeepDrop(NavGrid grid, int maxDrop, boolean drowns,
            int x, int y, int z) {
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
            if (drowns && landing == CellType.WATER
                    && grid.cell(nx, floor - 1, nz) != CellType.GROUND) {
                return true; // deep enough that stepping in is swimming, not wading
            }
        }
        return false;
    }

    /**
     * Whether a grid satisfies one {@link CellNeed} — the question the follower asks of the LIVE
     * world at each new node ({@code Navigator.stillHolds}), asked of a snapshot instead. The pair
     * must agree; they are separate because the follower must notice where the world has diverged
     * from the planning grid, so it cannot be handed that grid.
     *
     * <p>Asked OF the planning grid it checks a route against its own integrity contract: every
     * edge the search emits must already meet what {@link PathIntegrity} says it needs. A path that
     * fails this was never walkable.
     *
     * <p><b>Wading counts as footing</b>, as everywhere in the engine ({@code Pathfinder.footing}):
     * water a body can stand up in is ground to every land move.
     */
    public static boolean satisfies(NavGrid grid, CellNeed need) {
        CellType here = grid.cell(need.x(), need.y(), need.z());
        return switch (need.need()) {
            case CLEAR -> here == CellType.PASSABLE;
            case WATER -> here == CellType.WATER;
            case ROOM -> here == CellType.PASSABLE || here == CellType.WATER;
            case FOOTING -> here == CellType.STEP
                    || ((here == CellType.PASSABLE || here == CellType.WATER)
                            && grid.cell(need.x(), need.y() - 1, need.z()) == CellType.GROUND);
        };
    }
}
