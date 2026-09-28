package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A grid in which some doors, or all of them, are walls: {@link CellType#OBSTACLE}, which blocks a
 * body and cannot be stood on, as a fence does. How the enclosure check asks what a space would be
 * with those doors out of the way, without a second capture or a door rule inside the search.
 */
final class ShutDoors implements NavGrid {

    private final NavGrid base;
    /** Every door cell made a wall, packed; null when all of them are. */
    private final Set<Long> walled;

    private ShutDoors(NavGrid base, Set<Long> walled) {
        this.base = base;
        this.walled = walled;
    }

    /** Every door, gate and hatch a wall, open or shut. */
    static NavGrid all(NavGrid base) {
        return new ShutDoors(base, null);
    }

    /** These doors walls, each named by any of its cells; every other door as it stands. */
    static NavGrid of(NavGrid base, Collection<Pos> doors) {
        Set<Long> cells = new HashSet<>();
        for (Pos door : doors) {
            // A door is two cells tall and a gate one: wall the whole run, up and down.
            for (int y = door.y(); base.cell(door.x(), y, door.z()) == CellType.DOOR; y--) {
                cells.add(Pathfinder.pack(door.x(), y, door.z()));
            }
            for (int y = door.y() + 1; base.cell(door.x(), y, door.z()) == CellType.DOOR; y++) {
                cells.add(Pathfinder.pack(door.x(), y, door.z()));
            }
        }
        return new ShutDoors(base, cells);
    }

    private boolean walled(int x, int y, int z) {
        return this.walled == null
                ? this.base.cell(x, y, z) == CellType.DOOR
                : this.walled.contains(Pathfinder.pack(x, y, z));
    }

    @Override
    public CellType cell(int x, int y, int z) {
        CellType type = this.base.cell(x, y, z);
        return type == CellType.DOOR && walled(x, y, z) ? CellType.OBSTACLE : type;
    }

    @Override
    public double surface(int x, int y, int z) {
        return this.base.surface(x, y, z);
    }

    @Override
    public int ramps(int x, int y, int z) {
        return this.base.ramps(x, y, z);
    }

    @Override
    public int doorway(int x, int y, int z) {
        return walled(x, y, z) ? 0 : this.base.doorway(x, y, z);
    }

    @Override
    public boolean hatch(int x, int y, int z) {
        return this.walled != null && this.base.hatch(x, y, z);
    }

    @Override
    public boolean climbFloor(int x, int y, int z) {
        return this.base.climbFloor(x, y, z);
    }

    @Override
    public boolean hasDoors() {
        return this.walled != null && this.base.hasDoors();
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return this.base.inBounds(x, y, z);
    }
}
