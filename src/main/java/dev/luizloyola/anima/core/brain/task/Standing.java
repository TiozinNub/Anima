package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.NavGrids;
import java.util.Optional;

/**
 * Where a body could stand and stay — what a drive picking somewhere to <em>be</em> must settle
 * before it picks. Beside {@link Comfort} for the same reason {@code Comfort} is beside
 * {@link WanderStep}: the wander needed the term, and the next drive that has to choose a spot will
 * want it too. {@code Comfort} prices a spot; this says whether the spot is one at all.
 *
 * <p><b>"Could stand" is about being there, not about getting there.</b> Nothing here asks whether
 * the cell is reachable — that answer costs a whole search and belongs to the pathfinder — and
 * nothing here asks whether it is a spot worth wanting, which is {@code Comfort}'s.
 *
 * <p><b>Deliberately stricter than the engine's footing.</b> Wading is footing everywhere else
 * ({@link NavGrids#satisfies}, {@code Pathfinder}) and rightly so, because an errand crossing a
 * stream is fine. Idling in the stream for the five to fifteen seconds of a wander beat is not, so
 * this asks for dry land. See {@code docs/superpowers/specs/2026-08-23-wander-footing-design.md}.
 */
public final class Standing {

    private Standing() {
    }

    /** Whether this body could stand in this exact cell — see the class doc for what "could" excludes. */
    public static boolean standable(NavGrid grid, MoveCapabilities body, int x, int y, int z) {
        if (!grid.inBounds(x, y, z)) {
            return false; // an unloaded chunk is not "probably fine"
        }
        CellType here = grid.cell(x, y, z);
        // Strictly PASSABLE over GROUND, where CellNeed.FOOTING would also take WATER over GROUND:
        // that one spare word is the whole of the dry-land ruling, and it excludes every WATER cell
        // without naming one. DANGER (lava, fire, magma, cactus) and OBSTACLE (fences, walls) are
        // neither PASSABLE nor GROUND nor STEP, so they fall out here too.
        // Waterlogging is invisible to CellType, so a waterlogged slab reads dry. Accepted: the
        // alternative is a second vocabulary for one cosmetic case.
        boolean footing = here == CellType.STEP
                || (here == CellType.PASSABLE && grid.cell(x, y - 1, z) == CellType.GROUND);
        if (!footing) {
            return false;
        }
        int top = y + body.topCell(grid.surface(x, y, z));
        for (int cell = y + 1; cell <= top; cell++) {
            if (grid.cell(x, cell, z) != CellType.PASSABLE) {
                return false;
            }
        }
        return !NavGrids.isNearDeepDrop(grid, body.maxDrop(), x, y, z);
    }

    /**
     * Where this body could stand in this column, nearest to {@code preferredY} within {@code reach}
     * cells either way — empty when nowhere in that window works.
     *
     * <p>{@code reach} is the caller's to choose; this has no opinion about how far is reasonable.
     */
    public static Optional<Pos> spot(NavGrid grid, MoveCapabilities body, int x, int z,
            int preferredY, int reach) {
        for (int distance = 0; distance <= reach; distance++) {
            // Down before up at equal distance: a drop is cheaper than a climb, and a body that
            // would rather climb than descend hugs walls.
            if (standable(grid, body, x, preferredY - distance, z)) {
                return Optional.of(new Pos(x, preferredY - distance, z));
            }
            if (distance > 0 && standable(grid, body, x, preferredY + distance, z)) {
                return Optional.of(new Pos(x, preferredY + distance, z));
            }
        }
        return Optional.empty();
    }
}
