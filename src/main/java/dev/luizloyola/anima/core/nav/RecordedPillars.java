package dev.luizloyola.anima.core.nav;

import java.util.Set;

/**
 * {@link PathRequest#pillars()} as a search asks about it: once per probe of every expansion, so a
 * cell outside the pillars' box is turned away on bounds alone and the rest look up a primitive
 * table instead of boxing a {@code Long} into a {@code Set}. That lookup was 21% of the server
 * thread's ticks over 20 ms on the forest, 2026-10-02.
 */
final class RecordedPillars {

    private static final RecordedPillars NONE = new RecordedPillars(Set.of());

    private final CellTable.Flags cells;
    /** Every cell with a pillar block on a cardinal side, so a climb beside one asks once, not four times. */
    private final CellTable.Flags beside;
    private final boolean empty;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;

    static RecordedPillars of(Set<Long> packed) {
        return packed.isEmpty() ? NONE : new RecordedPillars(packed);
    }

    private RecordedPillars(Set<Long> packed) {
        this.empty = packed.isEmpty();
        this.cells = new CellTable.Flags(packed.size() * 2);
        this.beside = new CellTable.Flags(packed.size() * 8);
        int x1 = Integer.MAX_VALUE;
        int x2 = Integer.MIN_VALUE;
        int y1 = Integer.MAX_VALUE;
        int y2 = Integer.MIN_VALUE;
        int z1 = Integer.MAX_VALUE;
        int z2 = Integer.MIN_VALUE;
        for (long cell : packed) {
            int x = Pathfinder.unpackX(cell);
            int y = Pathfinder.unpackY(cell);
            int z = Pathfinder.unpackZ(cell);
            this.cells.put(cell, true);
            this.beside.put(Pathfinder.pack(x, y, z - 1), true);
            this.beside.put(Pathfinder.pack(x, y, z + 1), true);
            this.beside.put(Pathfinder.pack(x - 1, y, z), true);
            this.beside.put(Pathfinder.pack(x + 1, y, z), true);
            x1 = Math.min(x1, x);
            x2 = Math.max(x2, x);
            y1 = Math.min(y1, y);
            y2 = Math.max(y2, y);
            z1 = Math.min(z1, z);
            z2 = Math.max(z2, z);
        }
        this.minX = x1;
        this.maxX = x2;
        this.minY = y1;
        this.maxY = y2;
        this.minZ = z1;
        this.maxZ = z2;
    }

    boolean isEmpty() {
        return this.empty;
    }

    boolean contains(int x, int y, int z) {
        return within(x, y, z, 0) && this.cells.get(Pathfinder.pack(x, y, z)) == CellTable.Flags.TRUE;
    }

    /** Whether a cardinal side of this cell holds a recorded pillar block. */
    boolean beside(int x, int y, int z) {
        return within(x, y, z, 1) && this.beside.get(Pathfinder.pack(x, y, z)) == CellTable.Flags.TRUE;
    }

    private boolean within(int x, int y, int z, int margin) {
        return y >= this.minY && y <= this.maxY
                && x >= this.minX - margin && x <= this.maxX + margin
                && z >= this.minZ - margin && z <= this.maxZ + margin;
    }
}
