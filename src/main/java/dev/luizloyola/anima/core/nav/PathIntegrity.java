package dev.luizloyola.anima.core.nav;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the completion-critical cells of a path <em>edge</em> — the "relevant blocks" the
 * follower watches a few nodes ahead of the body (see {@code Navigator}'s integrity check). Keyed
 * on the move, and for the common case it is the cells the feet cross, not just the destination:
 *
 * <ul>
 *   <li><b>Level ground moves</b> ({@link MoveType#WALK}: step, diagonal or multi-cell stride):
 *       every cell of the {@link #lineCells Bresenham line} between the waypoints must keep
 *       {@link CellNeed.Need#FOOTING} under a {@link CellNeed.Need#CLEAR} body column. Watching
 *       only the destination missed blocks pulled from the middle of a stride — a 3-cell stride
 *       leaves two of every three deck cells between waypoints.
 *   <li><b>Water moves</b> ({@link MoveType#inWater()}): the destination feet cell stays
 *       {@link CellNeed.Need#WATER} with {@link CellNeed.Need#ROOM} above — room, not air, because
 *       a diving body's own column is water.
 *   <li><b>Leaps</b> ({@link MoveType#LEAP}): the landing plus the flight arc — takeoff headroom
 *       and a clear body-height+1 corridor over every gap column, so a wall built into the arc is
 *       caught too.
 *   <li><b>Other vertical moves</b> ({@link MoveType#DROP}, {@link MoveType#JUMP}): the destination
 *       only; the drop shaft and the block a jump clears are left to the reactive stuck/stray net.
 *   <li><b>Run-ups</b> ({@link MoveType#RUNUP}): the line rule when level, the destination rule
 *       when it rises onto its takeoff. Either way the takeoff is watched again, as the next leap's
 *       launch cell.
 * </ul>
 *
 * <p>Re-derived from waypoint geometry rather than recorded by the search, so it mirrors what
 * {@link Pathfinder}'s level-move generators require; {@code PathIntegrityTest} pins the shape. If
 * the move vocabulary grows a case this misses, source the cells from the generators directly.
 */
public final class PathIntegrity {
    private PathIntegrity() {}

    /**
     * The cells whose classification the edge from {@code from} to {@code to} depends on, given the
     * agent's body height — see the class doc for the per-move rule.
     */
    public static List<CellNeed> edgeNeeds(Waypoint from, Waypoint to, MoveCapabilities profile) {
        List<CellNeed> needs = new ArrayList<>();
        if (to.move().inWater()) {
            // Feet in water, and room for the rest of the body — above a swimmer that is water as
            // often as air, so the column asks for ROOM and not CLEAR. Demanding air failed every
            // DIVE and every submerged crossing on its own first tick, re-planning the same route.
            needs.add(new CellNeed(to.x(), to.y(), to.z(), CellNeed.Need.WATER));
            for (int i = 1; i <= profile.topCell(0.0); i++) {
                needs.add(new CellNeed(to.x(), to.y() + i, to.z(), CellNeed.Need.ROOM));
            }
            return needs;
        }
        if (to.move() == MoveType.WALK
                || (to.move() == MoveType.RUNUP && from.y() == to.y())) {
            // Level ground move: standable floor + body column under every cell the feet cross.
            //
            // Each END is read at its own level; only the cells between them take the
            // destination's. "Level" means the feet climb no more than STEP_UP, so a slab step is a
            // walk with endpoints in different cells — reading the whole line at the destination's
            // level demanded footing one cell ABOVE the slab and broke 23 gauntlet stations,
            // invisibly, because re-planning hands back the identical route. Only a stride has
            // middles, and it is level by construction (walkableFlank measures them against one y).
            //
            // A level RUNUP belongs here — a step, diagonal or stride onto a takeoff, re-marked so
            // the follower runs it, deck cells and all. One that RISES onto its takeoff is a jump.
            List<int[]> line = lineCells(from.x(), from.z(), to.x(), to.z());
            addStandable(needs, from.x(), from.y(), from.z(), profile, from.surface16() / 16.0);
            for (int i = 1; i < line.size(); i++) {
                addStandable(needs, line.get(i)[0], to.y(), line.get(i)[1], profile,
                        to.surface16() / 16.0);
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
