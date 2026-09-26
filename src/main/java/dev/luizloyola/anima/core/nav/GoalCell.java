package dev.luizloyola.anima.core.nav;

/**
 * Where in its column a walk to a named cell should end: the nearest cell a body can stand in,
 * reached without passing through anything solid.
 *
 * <p>A goal named inside a block — a click lands on a face, a height read names the top block —
 * climbs out to the cell above; an open goal falls through air to the first floor. Neither crosses
 * solid ground. The scan this replaced went down through anything that was not an obstacle, and a
 * walk to the foot of a tree whose head cell was leaves ended in a cave seven blocks under it, which
 * the legs called arriving (2026-09-10, reproduced 2026-09-25).
 */
public final class GoalCell {

    /** How far an open goal may fall to its floor. Clicks and height reads are rarely this far off. */
    static final int DROP_SCAN = 12;
    /**
     * How far a goal named inside a block may climb out. Two: the block a click or a height read
     * names, and one under it. A goal deeper in than that is inside a trunk or a hill, and climbing
     * out of it would send the walk up the tree.
     */
    static final int CLIMB = 2;

    private GoalCell() {
    }

    /**
     * The y to walk to in column {@code (x, z)} for a goal named at {@code y}, or {@code y} itself when
     * nothing standable is in reach — the search then gets as near as it can, which is the honest
     * answer for a goal a body cannot stand in.
     */
    public static int groundY(NavGrid grid, int x, int y, int z, MoveCapabilities body) {
        if (solid(grid.cell(x, y, z))) {
            for (int up = y + 1; up <= y + CLIMB; up++) {
                if (!solid(grid.cell(x, up, z))) {
                    return Pathfinder.standable(grid, body, x, up, z) ? up : y;
                }
            }
            return y;
        }
        for (int down = y; down > y - DROP_SCAN; down--) {
            if (Pathfinder.standable(grid, body, x, down, z)) {
                return down;
            }
            CellType here = grid.cell(x, down, z);
            if (body.canSwim() && here == CellType.WATER
                    && grid.cell(x, down + 1, z) == CellType.PASSABLE) {
                return down; // the water surface — a swimmer floats here
            }
            if (here != CellType.PASSABLE) {
                return y; // ground, water or danger under an open goal: no floor in reach
            }
        }
        return y;
    }

    private static boolean solid(CellType type) {
        return type == CellType.GROUND || type == CellType.OBSTACLE;
    }
}
