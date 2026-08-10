package dev.luizloyola.anima.core.nav;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the completion-critical cells of a path <em>edge</em> — the "relevant blocks" the
 * follower watches ahead of the body (see {@code Navigator}'s integrity check). Keyed on the move,
 * and for the common case it is the cells the feet cross, not just the destination:
 *
 * <ul>
 *   <li><b>Level ground moves</b> ({@link MoveType#WALK} — step, diagonal or stride): every cell on
 *       the {@link #lineCells Bresenham line} needs {@link CellNeed.Need#FOOTING} and a
 *       {@link CellNeed.Need#CLEAR} column. Destination-only missed a block pulled from the
 *       <em>middle</em> of a stride, and the follower walked off it.
 *   <li><b>Swim moves</b> ({@link MoveType#SWIM}): the destination feet cell stays
 *       {@link CellNeed.Need#WATER} with a clear body above the waterline (the surface float).
 *   <li><b>Leaps</b> ({@link MoveType#LEAP}): the landing plus the flight arc — takeoff headroom
 *       and a clear body-height+1 corridor over every gap column — so a wall in the arc is caught.
 *   <li><b>Other vertical moves</b> ({@link MoveType#DROP}, {@link MoveType#JUMP}): destination
 *       standability only; in-flight failures land as stumbles the reactive stuck/stray net already
 *       recovers from.
 * </ul>
 *
 * <p>Re-derived from waypoint geometry rather than recorded by the search, so it mirrors what
 * {@link Pathfinder}'s level-move generators require; {@code PathIntegrityTest} pins the shape. If
 * the move vocabulary grows a case this misses, source the cells from the generators instead.
 */
public final class PathIntegrity {
    private PathIntegrity() {}

    /**
     * The cells whose classification the edge from {@code from} to {@code to} depends on, given the
     * agent's body height — see the class doc for the per-move rule.
     */
    public static List<CellNeed> edgeNeeds(Waypoint from, Waypoint to, MoveCapabilities profile) {
        List<CellNeed> needs = new ArrayList<>();
        if (to.move() == MoveType.SWIM) {
            // Surface float: feet in water, the rest of the body clear above the waterline.
            needs.add(new CellNeed(to.x(), to.y(), to.z(), CellNeed.Need.WATER));
            for (int i = 1; i <= profile.topCell(0.0); i++) {
                needs.add(new CellNeed(to.x(), to.y() + i, to.z(), CellNeed.Need.CLEAR));
            }
            return needs;
        }
        if (to.move() == MoveType.WALK) {
            // Level ground move: watch the standable floor + body under every cell the feet cross,
            // at this waypoint's level (walks/diagonals/strides are all same-level).
            for (int[] cell : lineCells(from.x(), from.z(), to.x(), to.z())) {
                addStandable(needs, cell[0], to.y(), cell[1], profile, to.surface16() / 16.0);
            }
            return needs;
        }
        if (to.move() == MoveType.LEAP) {
            // A leap is the landing PLUS the flight arc — a wall built into the arc stops it
            // mid-air. Mirrors {@code Pathfinder.leapNeighbor}: takeoff headroom over the launch
            // cell, and a clear body-height+1 corridor (the arc rises a block) over every gap
            // column — cardinal and same-level, so those are the cells strictly between the
            // waypoints, all at the takeoff's y. The gap floor is not watched: filling
            // it does not break the leap, only its cost.
            addStandable(needs, to.x(), to.y(), to.z(), profile, to.surface16() / 16.0); // the landing
            int y = from.y();
            int overhead = profile.topCell(from.surface16() / 16.0) + 1;
            needs.add(new CellNeed(from.x(), y + overhead, from.z(), CellNeed.Need.CLEAR)); // takeoff headroom
            int sx = Integer.signum(to.x() - from.x());
            int sz = Integer.signum(to.z() - from.z());
            for (int gx = from.x() + sx, gz = from.z() + sz; gx != to.x() || gz != to.z(); gx += sx, gz += sz) {
                for (int i = 0; i <= overhead; i++) {
                    needs.add(new CellNeed(gx, y + i, gz, CellNeed.Need.CLEAR));
                }
            }
            return needs;
        }
        // Other vertical move (drop, jump): destination standability only. The shaft a drop falls
        // through / the block a jump clears are v1-out-of-scope, left to the reactive stuck/stray net.
        addStandable(needs, to.x(), to.y(), to.z(), profile, to.surface16() / 16.0);
        return needs;
    }

    /**
     * Appends the standability cells of feet-cell {@code (x,y,z)}: footing at the feet, a clear
     * column above.
     *
     * <p>{@link CellNeed.Need#FOOTING} rather than naming the floor's cell and type, because where
     * the floor lives depends on how it is built — under the feet for a full block, in the feet
     * cell for a slab — and this derivation has no grid to ask. The demand covers both, and a floor
     * rebuilt differently that walks the same.
     */
    private static void addStandable(List<CellNeed> needs, int x, int y, int z,
                                     MoveCapabilities profile, double surface) {
        needs.add(new CellNeed(x, y, z, CellNeed.Need.FOOTING));
        // The waypoint's own surface sizes the column, since where the feet sit inside the cell
        // decides how far up the body reaches — the arithmetic that admitted this cell.
        for (int i = 1; i <= profile.topCell(surface); i++) {
            needs.add(new CellNeed(x, y + i, z, CellNeed.Need.CLEAR));
        }
    }

    /**
     * The horizontal cells a straight segment from {@code (x0,z0)} to {@code (x1,z1)} crosses — an
     * integer Bresenham line, deterministic and allocation-light, core's own rather than
     * Minecraft's. Endpoints included, one cell per step: a pure diagonal steps corner-to-corner,
     * the feet crossing the diagonal cells and not the grazed corners.
     */
    static List<int[]> lineCells(int x0, int z0, int x1, int z1) {
        List<int[]> cells = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            cells.add(new int[]{x, z});
            if (x == x1 && z == z1) {
                return cells;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }
}
