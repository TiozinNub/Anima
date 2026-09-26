package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.store.Store;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a placed station can go: the cell asked for when something can stand there, else the
 * nearest one around it that can. Shared by a yard's chest ({@link EnsureStore}) and a base's
 * stations ({@link PutDown}), so both follow the same rules about floors and lids.
 */
final class Ground {

    /** The eight horizontal neighbours, sides first — a chest in a corner is awkward to reach. */
    static final int[][] SIDES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** How far up and down a column is searched for the surface — a storey either way. */
    private static final int COLUMN_REACH = 12;

    private Ground() {
    }

    /**
     * The asked-for cell when a station can stand there, else the closest cell around it that can,
     * {@code rings} out, nearest first. Falls back to the asked-for cell, which lets the placer
     * refuse and the round re-derive rather than inventing somewhere far away.
     */
    static Pos near(BrainContext ctx, Pos wanted, int rings) {
        // The COLUMN first, then the neighbours' columns. An operator points at a spot from
        // wherever they are standing, and on real ground that is routinely a storey out — a
        // yard asked for at y 73 over ground at y 63 left a settler "arriving" ten blocks
        // beneath a cell she could never reach, re-deriving for ever (in-world, 2026-08-20).
        Pos here = standable(ctx, wanted);
        if (here != null) {
            return here;
        }
        for (int ring = 1; ring <= rings; ring++) {
            for (int[] side : SIDES) {
                Pos cell = standable(ctx, new Pos(wanted.x() + side[0] * ring, wanted.y(),
                        wanted.z() + side[1] * ring));
                if (cell != null) {
                    return cell;
                }
            }
        }
        return wanted;
    }

    /**
     * Whether a station can stand in {@code cell}: empty, on something solid, nobody in it — and
     * never on top of another station.
     *
     * <p>A chest holds the next one up perfectly well, which is what turned a contested yard into a
     * COLUMN of four rather than a row (in-world, 2026-08-25, at {@code (-690, 72..75, 893)}): each
     * settler found the cell taken, looked one higher, and found a floor. Only the bottom chest was
     * ever reachable. A workbench is the same trap one block over: a base's chest aimed at its centre
     * climbed onto the table standing there.
     */
    static boolean canHold(BrainContext ctx, Pos cell) {
        BlockProbe probe = ctx.percepts().blocks();
        if (probe.at(cell.x(), cell.y(), cell.z()) != BlockKind.AIR) {
            return false;
        }
        BlockKind floor = probe.at(cell.x(), cell.y() - 1, cell.z());
        return floor != BlockKind.AIR && !Store.isStore(floor) && floor != Workbench.BLOCK
                && !PlaceBlock.occupied(ctx, cell);
    }

    /**
     * Put {@code itemId} into {@code cell}, breaking what grows there first. A cell the chooser calls
     * empty may hold leaf litter, petals or a flower — walk-through, so {@link #canHold} takes it —
     * and the game will not build over those: a base's chest was refused beside its workbench on a
     * forest floor until somebody cleared the petals (2026-09-26).
     */
    static List<Task> clearAndPlace(BrainContext ctx, String itemId, Pos cell) {
        List<Task> steps = new ArrayList<>(2);
        if (!ctx.percepts().blocks().empty(cell.x(), cell.y(), cell.z())) {
            steps.add(new BreakBlock(cell.x(), cell.y(), cell.z()));
        }
        steps.add(new PlaceBlock(itemId, cell.x(), cell.y(), cell.z()));
        return steps;
    }

    /**
     * The cell in {@code column}'s vertical line that a station can stand in, reached without passing
     * through anything solid: out of a named block upward to the first open cell, or down through
     * open cells to the floor. Searched both ways regardless, a base's chest went through the ground
     * into an air pocket three blocks under its workbench (2026-09-26); an operator's hint a storey
     * up still falls to the floor under it.
     */
    private static @Nullable Pos standable(BrainContext ctx, Pos column) {
        BlockProbe probe = ctx.percepts().blocks();
        int x = column.x();
        int z = column.z();
        if (probe.at(x, column.y(), z) != BlockKind.AIR) {
            for (int y = column.y() + 1; y <= column.y() + COLUMN_REACH; y++) {
                if (probe.at(x, y, z) == BlockKind.AIR) {
                    Pos cell = new Pos(x, y, z);
                    return canHold(ctx, cell) ? cell : null;
                }
            }
            return null;
        }
        for (int y = column.y(); y >= column.y() - COLUMN_REACH; y--) {
            Pos cell = new Pos(x, y, z);
            if (canHold(ctx, cell)) {
                return cell;
            }
            if (probe.at(x, y - 1, z) != BlockKind.AIR) {
                return null; // a floor, and not one to build on: go no further down this column
            }
        }
        return null;
    }
}
