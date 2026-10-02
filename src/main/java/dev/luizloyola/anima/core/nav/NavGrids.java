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
            if (fallHurts(grid, maxDrop, drowns, nx, y, nz)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a body that steps into the open cell {@code (x,y,z)} and falls comes to harm: no
     * floor within {@code maxDrop} under it, a floor that hurts, or — to a body that drowns — water
     * too deep to stand up in.
     */
    private static boolean fallHurts(NavGrid grid, int maxDrop, boolean drowns, int x, int y, int z) {
        int floor = y - 1;
        int limit = y - maxDrop - 1;
        while (floor >= limit && grid.cell(x, floor, z) == CellType.PASSABLE) {
            floor--;
        }
        if (floor < limit) {
            return true; // open all the way past survivable depth
        }
        CellType landing = grid.cell(x, floor, z);
        if (landing == CellType.DANGER) {
            return true;
        }
        return drowns && landing == CellType.WATER && grid.cell(x, floor - 1, z) != CellType.GROUND;
    }

    /**
     * {@link #fallHurts} asked of a body: whether falling through the open cell {@code (x,y,z)}
     * hurts it — what a leap that falls short of its landing drops into.
     */
    public static boolean fallHurts(NavGrid grid, MoveCapabilities body, int x, int y, int z) {
        return fallHurts(grid, body.maxDrop(), !body.canSwim(), x, y, z);
    }

    /**
     * Whether a body standing in feet-cell {@code (x,y,z)} is one slip from harm: a cell beside it,
     * diagonals included and at any height the body fills, that hurts to touch
     * ({@link CellType#DANGER}) — and with {@code drops}, a neighbour open at its feet over a fall
     * that would hurt it ({@link #fallHurts}).
     *
     * <p>Wider than {@link #isNearDeepDrop}, which is the follower's throttle and looks only where a
     * cardinal step lands: a 0.6-wide body brushes the cells it passes at a corner, and a lava
     * stream beside a cave floor is harm at the feet, not under them — a settler strolling one cell
     * from it stepped in and burned (2026-10-02).
     */
    public static boolean besideHarm(NavGrid grid, MoveCapabilities body, int x, int y, int z,
            boolean drops) {
        int top = y + body.topCell(0.0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                for (int cell = y; cell <= top; cell++) {
                    if (grid.cell(nx, cell, nz) == CellType.DANGER) {
                        return true;
                    }
                }
                if (drops && grid.cell(nx, y, nz) == CellType.PASSABLE
                        && fallHurts(grid, body, nx, y, nz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a grid satisfies one {@link CellNeed} — asked by the follower of the LIVE world at each
     * new node, through a grid over the level rather than the planning grid, since it must notice
     * where the world has diverged from what was planned on. One rule for both: a follower that kept
     * its own copy refused the wading footing below, and re-planned every route through shallow water
     * every 20 ticks (2026-09-25).
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
            // A hatch is room to the climber whose head goes up into it, which is the only route
            // the planner puts through one.
            case CLEAR -> isOpen(here) || grid.hatch(need.x(), need.y(), need.z());
            case WATER -> here == CellType.WATER;
            case ROOM -> here == CellType.PASSABLE || here == CellType.WATER;
            case FOOTING -> hasFooting(grid, need, here);
            case HOLD -> here == CellType.CLIMB || grid.hatch(need.x(), need.y(), need.z())
                    || hasFooting(grid, need, here);
            case LAYABLE -> here == CellType.GROUND || grid.layable(need.x(), need.y(), need.z());
        };
    }

    /**
     * What a body's column may pass through: air, a climbable, a doorway. Body-blind, so a door
     * counts whichever way it is swung — a door someone shut across a route is the follower's to
     * swing, and one it cannot swing wedges it and re-plans.
     */
    private static boolean isOpen(CellType here) {
        return here == CellType.PASSABLE || here == CellType.CLIMB || here == CellType.DOOR;
    }

    private static boolean hasFooting(NavGrid grid, CellNeed need, CellType here) {
        if (here == CellType.STEP) {
            return true;
        }
        if (!isOpen(here) && here != CellType.WATER) {
            return false;
        }
        CellType below = grid.cell(need.x(), need.y() - 1, need.z());
        return below == CellType.GROUND
                || (below == CellType.CLIMB && grid.climbFloor(need.x(), need.y() - 1, need.z()));
    }
}
