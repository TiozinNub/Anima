package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.store.Store;
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
     * The cell in {@code column}'s vertical line that a station can stand in. Searched from the
     * asked-for height outward, nearest first, so a cell that is already right costs one read and
     * one in the air finds the floor under it.
     */
    private static @Nullable Pos standable(BrainContext ctx, Pos column) {
        for (int step = 0; step <= COLUMN_REACH; step++) {
            for (int dy : step == 0 ? new int[]{0} : new int[]{-step, step}) {
                Pos cell = new Pos(column.x(), column.y() + dy, column.z());
                if (canHold(ctx, cell)) {
                    return cell;
                }
            }
        }
        return null;
    }
}
