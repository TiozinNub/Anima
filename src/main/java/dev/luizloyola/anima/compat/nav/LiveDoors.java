package dev.luizloyola.anima.compat.nav;

import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * A capture with its doors and hatches read again from the world as they stand now. Doors are the
 * blocks that change under a plan, so a snapshot kept from a walk that has just ended is fine for
 * everything but them.
 *
 * <p>Built on the server thread (it reads the live level once, at construction) and immutable after,
 * so a worker may search it. A door placed since the capture is not seen; one broken or swung is.
 */
public final class LiveDoors implements NavGrid {

    /** One re-read cell: everything a search asks of a door or a hatch. */
    private record Cell(CellType type, double surface, int doorway, boolean hatch,
                        boolean climbFloor) {
    }

    private final WorldSnapshot base;
    private final Map<Long, Cell> now = new HashMap<>();
    private final boolean doors;

    private LiveDoors(WorldSnapshot base, Level level) {
        this.base = base;
        boolean anyDoor = false;
        for (BlockPos pos : base.doorCells()) {
            if (!level.isLoaded(pos)) {
                continue; // keep what was captured: reading an unloaded chunk would load it
            }
            Cell cell = new Cell(WorldSnapshot.classifyAt(level, pos),
                    WorldSnapshot.surfaceAt(level, pos), WorldSnapshot.doorwayAt(level, pos),
                    WorldSnapshot.hatchAt(level, pos), WorldSnapshot.climbFloorAt(level, pos));
            this.now.put(pos.asLong(), cell);
            anyDoor |= cell.type() == CellType.DOOR;
        }
        this.doors = anyDoor || base.hasDoors();
    }

    /** {@code snapshot} with its doors as they stand now. Server thread. */
    public static NavGrid over(WorldSnapshot snapshot, Level level) {
        return new LiveDoors(snapshot, level);
    }

    private @Nullable Cell at(int x, int y, int z) {
        return this.now.isEmpty() ? null : this.now.get(BlockPos.asLong(x, y, z));
    }

    @Override
    public CellType cell(int x, int y, int z) {
        Cell cell = at(x, y, z);
        return cell == null ? this.base.cell(x, y, z) : cell.type();
    }

    @Override
    public double surface(int x, int y, int z) {
        Cell cell = at(x, y, z);
        return cell == null ? this.base.surface(x, y, z) : cell.surface();
    }

    @Override
    public int ramps(int x, int y, int z) {
        return this.base.ramps(x, y, z);
    }

    @Override
    public int doorway(int x, int y, int z) {
        Cell cell = at(x, y, z);
        return cell == null ? this.base.doorway(x, y, z) : cell.doorway();
    }

    @Override
    public boolean hatch(int x, int y, int z) {
        Cell cell = at(x, y, z);
        return cell == null ? this.base.hatch(x, y, z) : cell.hatch();
    }

    @Override
    public boolean climbFloor(int x, int y, int z) {
        Cell cell = at(x, y, z);
        return cell == null ? this.base.climbFloor(x, y, z) : cell.climbFloor();
    }

    @Override
    public boolean hasDoors() {
        return this.doors;
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return this.base.inBounds(x, y, z);
    }
}
