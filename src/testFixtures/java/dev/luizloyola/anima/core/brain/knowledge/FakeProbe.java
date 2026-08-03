package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A block world for perception tests: a sparse block map over a flat OTHER ground plane at
 * {@link #GROUND_Y}, with markable unloaded columns and hidden (ray-blocked) cells. Counts
 * every {@code surfaceY}/{@code at} call so tests can assert the read budget from outside.
 */
public final class FakeProbe implements BlockProbe {
    public static final int GROUND_Y = 63;

    private final Map<Pos, BlockKind> blocks = new HashMap<>();
    private final Set<Column> unloaded = new HashSet<>();
    private final Set<Pos> hidden = new HashSet<>();
    public int reads;

    public void set(int x, int y, int z, BlockKind kind) {
        blocks.put(new Pos(x, y, z), kind);
    }

    public void clear(int x, int y, int z) {
        blocks.remove(new Pos(x, y, z));
    }

    /**
     * A little oak with its trunk at column (x, z) on the ground: 4 logs (y 64–67), a ring of
     * 8 leaves around the trunk top (y 67), a full 3×3 leaf cap (y 68). 4 logs, 17 leaves;
     * every canopy column's surface is a leaf at y 68.
     */
    public void placeOak(int x, int z) {
        for (int y = 64; y <= 67; y++) {
            set(x, y, z, BlockKind.LOG);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    set(x + dx, 67, z + dz, BlockKind.LEAVES);
                }
                set(x + dx, 68, z + dz, BlockKind.LEAVES);
            }
        }
    }

    public void removeOak(int x, int z) {
        for (int y = 64; y <= 68; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    blocks.remove(new Pos(x + dx, y, z + dz));
                }
            }
        }
    }

    public void markUnloaded(int x, int z) {
        unloaded.add(new Column(x, z));
    }

    public void hide(Pos target) {
        hidden.add(target);
    }

    public void reveal(Pos target) {
        hidden.remove(target);
    }

    @Override
    public int surfaceY(int x, int z) {
        reads++;
        if (unloaded.contains(new Column(x, z))) {
            return Integer.MIN_VALUE;
        }
        int top = GROUND_Y;
        for (Pos p : blocks.keySet()) {
            if (p.x() == x && p.z() == z && p.y() > top) {
                top = p.y();
            }
        }
        return top;
    }

    @Override
    public BlockKind at(int x, int y, int z) {
        reads++;
        if (unloaded.contains(new Column(x, z))) {
            return BlockKind.UNKNOWN;
        }
        BlockKind kind = blocks.get(new Pos(x, y, z));
        if (kind != null) {
            return kind;
        }
        return y <= GROUND_Y ? BlockKind.OTHER : BlockKind.AIR;
    }

    /**
     * A ray's view of a cell, derived from the same sparse map: solids stop it, and everything an
     * eye sees through (air, leaves, water) lets it past. Ground level and below is solid, which
     * is what makes a downward ray die on the floor here exactly as it would in a world.
     */
    @Override
    public Sight sightAt(int x, int y, int z) {
        reads++;
        if (unloaded.contains(new Column(x, z))) {
            return Sight.OUTSIDE;
        }
        BlockKind kind = blocks.get(new Pos(x, y, z));
        if (kind == null) {
            return y <= GROUND_Y ? Sight.BLOCKED : Sight.CLEAR;
        }
        return switch (kind) {
            case LOG, OTHER -> Sight.BLOCKED;
            case LEAVES, WATER -> Sight.VEILED;
            case AIR, UNKNOWN -> Sight.CLEAR;
        };
    }

    @Override
    public boolean visibleFromEyes(Pos target) {
        return !hidden.contains(target);
    }

    /**
     * Line of sight between two cells: blocked when EITHER end is marked hidden. Crude on purpose —
     * a cover test says "this spot is behind the wall" without building a wall.
     */
    @Override
    public boolean sightClearBetween(Pos from, Pos to) {
        return !hidden.contains(from) && !hidden.contains(to);
    }
}
