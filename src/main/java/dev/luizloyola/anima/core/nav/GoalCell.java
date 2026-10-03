package dev.luizloyola.anima.core.nav;

/**
 * Where in its column a walk to a named cell should end: the nearest cell a body can stand in,
 * reached without passing through anything solid.
 *
 * <p>A goal named inside a block — a click lands on a face, a height read names the top block —
 * climbs out to the cell above; an open goal falls through air to the floor under it, one cell at
 * most. Neither crosses solid ground. The scan this replaced went down through anything that was
 * not an obstacle, and a walk to the foot of a tree whose head cell was leaves ended in a cave seven
 * blocks under it, which the legs called arriving (2026-09-10, reproduced 2026-09-25).
 */
public final class GoalCell {

    /** How far down a goal is scanned for its floor: a ladder's foot, or {@link #floorY}'s fall. */
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
     *
     * <p>Through open air the goal falls at most one cell: the head cell of a body standing under it
     * (Luiz, 2026-10-02). Further up it is a cell in the air, not a place: a stand eight above a pit
     * floor "arrived" on the floor, out of the placer's reach, and was walked to again every 13 ticks
     * (run/normal, 2026-10-02). A caller that means "the floor under there" asks {@link #floorY}.
     */
    public static int groundY(NavGrid grid, int x, int y, int z, MoveCapabilities body) {
        return lower(grid, x, y, z, body, 1);
    }

    /**
     * The floor under a cell named loosely — a dropped item, another body, a point on a heading:
     * {@link #groundY} with the fall through open air allowed down to {@link #DROP_SCAN}, so the
     * walk is asked for a cell a body can stand in.
     */
    public static int floorY(NavGrid grid, int x, int y, int z, MoveCapabilities body) {
        return lower(grid, x, y, z, body, DROP_SCAN);
    }

    private static int lower(NavGrid grid, int x, int y, int z, MoveCapabilities body, int fall) {
        if (solid(grid.cell(x, y, z))) {
            for (int up = y + 1; up <= y + CLIMB; up++) {
                if (!solid(grid.cell(x, up, z))) {
                    return Pathfinder.standable(grid, body, x, up, z) ? up : y;
                }
            }
            return y;
        }
        int fallen = 0;
        for (int down = y; down > y - DROP_SCAN; down--) {
            if (Pathfinder.standable(grid, body, x, down, z)) {
                return fallen <= fall ? down : y;
            }
            CellType here = grid.cell(x, down, z);
            if (body.canSwim() && here == CellType.WATER
                    && grid.cell(x, down + 1, z) == CellType.PASSABLE) {
                return fallen <= fall ? down : y; // the water surface — a swimmer floats here
            }
            // A climbable is air to this scan: a goal named on a ladder or in vines ends on the
            // floor under it, since nobody can stand on the rung itself.
            if (here != CellType.PASSABLE && here != CellType.CLIMB) {
                return y; // ground, water or danger under an open goal: no floor in reach
            }
            if (here == CellType.PASSABLE) {
                fallen++; // a rung is climbed down, not fallen past
            }
        }
        return y;
    }

    private static boolean solid(CellType type) {
        return type == CellType.GROUND || type == CellType.OBSTACLE;
    }
}
