package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of a box somebody has actually covered, a 2-block square at a time.
 *
 * <p><b>Why sub-cell.</b> A cell is eight blocks and a Person's near field is eight blocks
 * ({@code places.near_radius}), so a near-field disc can never contain a whole cell unless it is
 * almost perfectly centred: a body at a cell's corner is 11.3 blocks from the far corner, three
 * past what it can individuate. Crediting the whole cell whenever its CENTRE was in range — which
 * is what walking credit did until 2026-08-23 — claims ground nobody looked at. Sixteen squares per
 * cell says what was covered, and unions across visits so a body crossing a cell from two sides
 * ends up knowing it.
 *
 * <p>Grid-aligned to {@code area.min()}. Two callers on different origins would each get a
 * different answer for the same ground, so everything that shares coverage shares an origin.
 */
public final class CoverageGrid {

    /**
     * Edge of one cell, in blocks. Matched to the glimpse grid — a sighting lands on an 8-block
     * cell, so a finer coverage grid would record a precision the evidence does not have.
     */
    public static final int CELL = 8;

    /** Squares along a cell's edge. Sixteen per cell, which is one {@code int} of bits. */
    public static final int SUB = 4;

    /** Edge of one square, in blocks. */
    public static final int SUB_SIZE = CELL / SUB;

    /** Every square of a cell — what a look that cleared it, or a write-off, banks. */
    public static final int FULL = 0xFFFF;

    /**
     * Fraction of a cell that has to be covered before it counts as known. A third was tried on the
     * old float model and reverted: a threshold loose enough to miss a tree misses it twice.
     */
    public static final double ENOUGH = 0.5;

    private final int originX;
    private final int originY;
    private final int originZ;
    private final int wide;
    private final int deep;
    private final int[] masks;

    public CoverageGrid(Region area) {
        this.originX = area.min().x();
        this.originY = area.min().y();
        this.originZ = area.min().z();
        this.wide = cellsAcross(area.max().x() - area.min().x() + 1);
        this.deep = cellsAcross(area.max().z() - area.min().z() + 1);
        this.masks = new int[wide * deep];
    }

    private static int cellsAcross(int blocks) {
        return Math.max(1, (blocks + CELL - 1) / CELL);
    }

    public int cells() {
        return masks.length;
    }

    /** The min corner of a cell, in world coordinates — the handle every caller names it by. */
    public Pos cornerOf(int cell) {
        return new Pos(originX + (cell / deep) * CELL, originY, originZ + (cell % deep) * CELL);
    }

    /** The cell a world column falls in, or -1 when it is off the grid. */
    public int cellAt(int x, int z) {
        int cx = Math.floorDiv(x - originX, CELL);
        int cz = Math.floorDiv(z - originZ, CELL);
        if (cx < 0 || cx >= wide || cz < 0 || cz >= deep) {
            return -1;
        }
        return cx * deep + cz;
    }

    public int mask(int cell) {
        return masks[cell];
    }

    public double confidence(int cell) {
        return Integer.bitCount(masks[cell]) / (double) (SUB * SUB);
    }

    public boolean settled(int cell) {
        return confidence(cell) >= ENOUGH;
    }

    public boolean allSettled() {
        for (int cell = 0; cell < masks.length; cell++) {
            if (!settled(cell)) {
                return false;
            }
        }
        return true;
    }

    public int settledCount() {
        int known = 0;
        for (int cell = 0; cell < masks.length; cell++) {
            if (settled(cell)) {
                known++;
            }
        }
        return known;
    }

    /**
     * Banks every square whose centre is within {@code radius} of {@code here}, horizontally.
     * Answers whether anything was new, so a caller can skip telling a sink that already knows.
     *
     * <p>Horizontal only: a body walking a slope covers the ground it passes over, and a height
     * difference is not a gap in what it saw.
     */
    public boolean markNear(Pos here, int radius) {
        boolean changed = false;
        long reach = (long) radius * radius;
        int fromX = Math.floorDiv(here.x() - radius - originX, CELL);
        int toX = Math.floorDiv(here.x() + radius - originX, CELL);
        int fromZ = Math.floorDiv(here.z() - radius - originZ, CELL);
        int toZ = Math.floorDiv(here.z() + radius - originZ, CELL);
        for (int cx = Math.max(0, fromX); cx <= Math.min(wide - 1, toX); cx++) {
            for (int cz = Math.max(0, fromZ); cz <= Math.min(deep - 1, toZ); cz++) {
                int cell = cx * deep + cz;
                if (masks[cell] == FULL) {
                    continue;
                }
                int add = 0;
                for (int sx = 0; sx < SUB; sx++) {
                    for (int sz = 0; sz < SUB; sz++) {
                        long dx = originX + cx * CELL + sx * SUB_SIZE + SUB_SIZE / 2 - here.x();
                        long dz = originZ + cz * CELL + sz * SUB_SIZE + SUB_SIZE / 2 - here.z();
                        if (dx * dx + dz * dz <= reach) {
                            add |= 1 << (sz * SUB + sx);
                        }
                    }
                }
                if ((masks[cell] | add) != masks[cell]) {
                    masks[cell] |= add;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Banks a whole cell by its min corner. False when that corner is off the grid. */
    public boolean markFull(Pos corner) {
        return markMask(corner, FULL);
    }

    /** Unions {@code mask} into the cell at {@code corner}. False when that corner is off the grid. */
    public boolean markMask(Pos corner, int mask) {
        int cell = cellAt(corner.x(), corner.z());
        if (cell < 0 || (masks[cell] | mask) == masks[cell]) {
            return false;
        }
        masks[cell] |= mask;
        return true;
    }

    /**
     * Corner → mask for every cell of this grid whose corner falls inside {@code area}, skipping
     * the untouched ones. What a sweep of a slice is handed so it starts already knowing its ground.
     */
    public Map<Pos, Integer> masksIn(Region area) {
        Map<Pos, Integer> out = new LinkedHashMap<>();
        for (int cell = 0; cell < masks.length; cell++) {
            if (masks[cell] == 0) {
                continue;
            }
            Pos corner = cornerOf(cell);
            if (corner.x() >= area.min().x() && corner.x() <= area.max().x()
                    && corner.z() >= area.min().z() && corner.z() <= area.max().z()) {
                out.put(corner, masks[cell]);
            }
        }
        return out;
    }

    /** Every touched cell, corner → mask — the party store's row. */
    public Map<Pos, Integer> masks() {
        Map<Pos, Integer> out = new LinkedHashMap<>();
        for (int cell = 0; cell < masks.length; cell++) {
            if (masks[cell] != 0) {
                out.put(cornerOf(cell), masks[cell]);
            }
        }
        return out;
    }
}
