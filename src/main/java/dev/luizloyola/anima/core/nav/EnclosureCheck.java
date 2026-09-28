package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Holes;
import dev.luizloyola.anima.core.brain.sense.Enclosure.Openness;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How the space around a body opens (spec: {@code 2026-09-28-shelter-design.md}).
 *
 * <p>Each question is a {@link Pathfinder#survey} over one grid, under a different rule for
 * doors: all of them walls, then as they stand to a body without hands, then as a hand swings
 * them. The first rule that lets the body reach the rim of the capture sets the verdict. Asked with
 * the body's own legs, so {@code CLOSED} is exactly what the survey calls sealed.
 *
 * <p>Several floods, not one, because a room is a few hundred cells and open ground settles on the
 * first. Asked of an immutable grid, it is safe on a worker: every wrapper is private to the call.
 */
public final class EnclosureCheck {

    /** A body that fits a gap one block wide and one high: a baby zombie, a cave spider. */
    static final double SMALL_HEIGHT = 0.9;

    /** How far over a head the check looks for a roof before calling it open to the sky. */
    private static final int ROOF_SCAN = 64;

    /** A door is entered from beside it, straight along its axis; a hatch from under or over it. */
    private static final int[][] AROUND = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private EnclosureCheck() {
    }

    /**
     * The verdict for a body standing at {@code (x, y, z)}.
     *
     * @param maxNodes the budget for each flood; one spent before an answer reads as a way out,
     *                 which is the safe failure: never a false shelter, never a false prison
     * @param now      the tick to stamp the answer with
     */
    public static Enclosure run(NavGrid grid, int x, int y, int z, MoveCapabilities body,
                                int maxNodes, long now) {
        Pos from = new Pos(x, y, z);
        MoveCapabilities paws = withHands(body, false);
        Confinement room = survey(ShutDoors.all(grid), from, paws, maxNodes);
        if (!room.sealed()) {
            return Enclosure.open(from, now);
        }
        Confinement asTheyStand = survey(grid, from, paws, maxNodes);

        Openness openness;
        List<Pos> toShut = List.of();
        Collection<Pos> space;
        NavGrid holesGrid = grid;
        if (asTheyStand.sealed()) {
            space = asTheyStand.region();
            openness = survey(grid, from, withHands(body, true), maxNodes).sealed()
                    ? Openness.CLOSED : Openness.OPENABLE;
        } else {
            openness = Openness.CLOSEABLE;
            toShut = doorsToShut(grid, from, room.region(), paws, maxNodes);
            holesGrid = ShutDoors.of(grid, toShut);
            Confinement shut = survey(holesGrid, from, paws, maxNodes);
            space = shut.sealed() ? shut.region() : List.of();
        }
        if (space.isEmpty()) {
            // Shutting every door out of the room did not close it: nothing here to shelter in.
            return new Enclosure(openness, Holes.NONE, false, from, Set.of(), toShut, List.of(),
                    now);
        }
        Holes holes = survey(holesGrid, from, small(paws), maxNodes).sealed()
                ? Holes.NONE : Holes.SMALL;
        return new Enclosure(openness, holes, roofed(grid, space, paws.clearCells()), from,
                new LinkedHashSet<>(space), toShut,
                nearestFirst(edgeDoors(grid, space, paws.clearCells()), from), now);
    }

    /**
     * The open doors out of the room whose far side leads out, directly or through other rooms.
     * Each is asked with every other door out of the room made a wall, so a door into a closed
     * cupboard is left open and two doors into one hall are both shut.
     */
    private static List<Pos> doorsToShut(NavGrid grid, Pos from, Collection<Pos> room,
                                         MoveCapabilities paws, int maxNodes) {
        List<Pos> edge = nearestFirst(edgeDoors(grid, room, paws.clearCells()), from);
        List<Pos> toShut = new ArrayList<>();
        for (Pos door : edge) {
            List<Pos> others = new ArrayList<>(edge);
            others.remove(door);
            if (!survey(ShutDoors.of(grid, others), from, paws, maxNodes).sealed()) {
                toShut.add(door);
            }
        }
        // Every way out of the room is a door, so one of them must lead out; a door this missed
        // (reached some way the edge scan does not look) is still shut by shutting them all.
        return toShut.isEmpty() ? edge : toShut;
    }

    /** Every door beside, over or under these cells, each by its lowest cell. */
    static List<Pos> edgeDoors(NavGrid grid, Collection<Pos> cells, int clearCells) {
        Set<Pos> doors = new LinkedHashSet<>();
        for (Pos cell : cells) {
            for (int[] d : AROUND) {
                int x = cell.x() + d[0];
                int z = cell.z() + d[1];
                for (int y = cell.y() - 1; y <= cell.y() + clearCells; y++) {
                    if (grid.cell(x, y, z) == CellType.DOOR) {
                        int bottom = y;
                        while (grid.cell(x, bottom - 1, z) == CellType.DOOR) {
                            bottom--;
                        }
                        doors.add(new Pos(x, bottom, z));
                    }
                }
            }
        }
        return new ArrayList<>(doors);
    }

    /** Whether every cell has something solid over the head before the sky, or the capture, ends. */
    private static boolean roofed(NavGrid grid, Collection<Pos> space, int clearCells) {
        for (Pos cell : space) {
            if (!roofedOver(grid, cell, clearCells)) {
                return false;
            }
        }
        return true;
    }

    private static boolean roofedOver(NavGrid grid, Pos cell, int clearCells) {
        for (int y = cell.y() + clearCells; y < cell.y() + clearCells + ROOF_SCAN; y++) {
            if (!grid.inBounds(cell.x(), y, cell.z())) {
                return false; // past the capture: no knowing, so no roof
            }
            switch (grid.cell(cell.x(), y, cell.z())) {
                case GROUND, OBSTACLE, STEP, DANGER -> {
                    return true;
                }
                default -> {
                    // air, water, a ladder, an open hatch: something could come down through it
                }
            }
        }
        return false;
    }

    private static List<Pos> nearestFirst(List<Pos> doors, Pos from) {
        List<Pos> sorted = new ArrayList<>(doors);
        sorted.sort(Comparator.<Pos>comparingInt(door -> distanceSq(door, from))
                .thenComparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        return sorted;
    }

    private static int distanceSq(Pos a, Pos b) {
        int dx = a.x() - b.x();
        int dy = a.y() - b.y();
        int dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Confinement survey(NavGrid grid, Pos from, MoveCapabilities body,
                                      int maxNodes) {
        return Pathfinder.survey(grid, new PathRequest(from.x(), from.y(), from.z(),
                from.x(), from.y(), from.z(), body, DangerField.NONE, NavDomain.EVERYWHERE,
                maxNodes, 0L, SetbackField.NONE));
    }

    private static MoveCapabilities withHands(MoveCapabilities body, boolean hands) {
        return new MoveCapabilities(body.height(), body.jumpHeight(), body.maxDrop(),
                body.maxLeap(), body.canSwim(), body.maxSubmerged(), hands, body.canClimb());
    }

    /** The same legs in a body one block high, with no hands. */
    private static MoveCapabilities small(MoveCapabilities paws) {
        return new MoveCapabilities(SMALL_HEIGHT, paws.jumpHeight(), paws.maxDrop(),
                paws.maxLeap(), paws.canSwim(), paws.maxSubmerged(), false, paws.canClimb());
    }
}
