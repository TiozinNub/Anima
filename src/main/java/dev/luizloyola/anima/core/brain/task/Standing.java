package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.NavGrids;
import java.util.Optional;
import java.util.Set;

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
        return refusal(grid, body, x, y, z) == STANDS;
    }

    /** Why this body could not stand in this cell, in a few words; empty when it could. */
    public static String whyNot(NavGrid grid, MoveCapabilities body, int x, int y, int z) {
        return switch (refusal(grid, body, x, y, z)) {
            case UNLOADED -> "not loaded";
            case NO_FOOTING -> "nothing to stand on (" + grid.cell(x, y, z).name().toLowerCase(java.util.Locale.ROOT)
                    + " over " + grid.cell(x, y - 1, z).name().toLowerCase(java.util.Locale.ROOT) + ")";
            case NO_HEADROOM -> "no room for the head";
            case DEEP_DROP -> "beside a drop the body would not survive";
            case FARMLAND -> "farmland underfoot";
            default -> "";
        };
    }

    private static final int STANDS = 0;
    private static final int UNLOADED = 1;
    private static final int NO_FOOTING = 2;
    private static final int NO_HEADROOM = 3;
    private static final int DEEP_DROP = 4;
    private static final int FARMLAND = 5;

    private static int refusal(NavGrid grid, MoveCapabilities body, int x, int y, int z) {
        if (!grid.inBounds(x, y, z)) {
            return UNLOADED; // an unloaded chunk is not "probably fine"
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
            return NO_FOOTING;
        }
        if (here == CellType.STEP && !body.treadsFarmland() && grid.farmland(x, y, z)) {
            return FARMLAND; // a body landing on it tramples the crop
        }
        int top = y + body.topCell(grid.surface(x, y, z));
        for (int cell = y + 1; cell <= top; cell++) {
            if (grid.cell(x, cell, z) != CellType.PASSABLE) {
                return NO_HEADROOM;
            }
        }
        // The body, not just its drop distance: what is being tested is lethality, and deep water
        // is not lethal to a swimmer. Body-blind, this refused every dry cell cardinally beside
        // open water — the whole shore of a lake, and every plank of a dock or bridge over one,
        // since the neighbour scan stops on the water and never finds ground under it.
        return NavGrids.isNearDeepDrop(grid, body, x, y, z) ? DEEP_DROP : STANDS;
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

    /** A body's eye over its feet, as a share of its height: 1.62 on a Person's 1.8. */
    public static final double EYE = 0.9;

    /**
     * Where this body could stand to reach the middle of {@code cell} from its eye: {@code preferred}
     * when that works, else the cell nearest it that does, the lower first at a tie. Never inside
     * {@code cell} or {@code also}, the other cells the work fills. Empty when nowhere in reach
     * works.
     */
    public static Optional<Pos> reaching(NavGrid grid, MoveCapabilities body, Pos cell, Set<Pos> also,
            Pos preferred, double reach) {
        double eye = body.height() * EYE;
        if (preferred != null && reaches(grid, body, preferred, cell, also, eye, reach)) {
            return Optional.of(preferred);
        }
        Pos from = preferred != null ? preferred : cell;
        int span = (int) Math.ceil(reach);
        int below = (int) Math.ceil(reach + eye);
        Pos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int y = cell.y() - below; y <= cell.y() + span; y++) {
            for (int z = cell.z() - span; z <= cell.z() + span; z++) {
                for (int x = cell.x() - span; x <= cell.x() + span; x++) {
                    Pos stand = new Pos(x, y, z);
                    long dx = x - from.x();
                    long dy = y - from.y();
                    long dz = z - from.z();
                    long distance = dx * dx + dy * dy + dz * dz;
                    if ((distance < bestDistance || distance == bestDistance && y < best.y())
                            && reaches(grid, body, stand, cell, also, eye, reach)) {
                        best = stand;
                        bestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether this body standing at {@code stand} reaches {@code cell} — see {@link #reaching}. */
    public static boolean reaches(NavGrid grid, MoveCapabilities body, Pos stand, Pos cell, Set<Pos> also,
            double reach) {
        return reaches(grid, body, stand, cell, also, body.height() * EYE, reach);
    }

    private static boolean reaches(NavGrid grid, MoveCapabilities body, Pos stand, Pos cell, Set<Pos> also,
            double eye, double reach) {
        double dx = cell.x() - stand.x();
        double dy = cell.y() + 0.5 - (stand.y() + eye);
        double dz = cell.z() - stand.z();
        if (dx * dx + dy * dy + dz * dz > reach * reach) {
            return false;
        }
        int top = (int) Math.ceil(body.height()) - 1;
        for (int up = 0; up <= top; up++) {
            Pos taken = new Pos(stand.x(), stand.y() + up, stand.z());
            if (taken.equals(cell) || also.contains(taken)) {
                return false;
            }
        }
        return standable(grid, body, stand.x(), stand.y(), stand.z());
    }
}
