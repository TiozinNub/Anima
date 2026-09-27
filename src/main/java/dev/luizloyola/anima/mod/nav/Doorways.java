package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.compat.agent.Arms;
import dev.luizloyola.anima.compat.nav.Doors;
import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.Doorway;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Waypoint;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The follower's hand on doors, gates and hatches: puts the next one on the route the way the route
 * needs it — by hand, or by the button, lever or pressure plate beside an iron door — and, once
 * through, leaves it the way it should be left.
 *
 * <p><b>How a door is left</b> (Luiz, 2026-09-26). A house's door is shut after passing, however it
 * was found. Any other door is put back as it was found: left open if it was open, shut again if it
 * was opened to pass. Bodies going through together leave it for each other, and the last one
 * through decides. Which doors are a house's is the consuming mod's to say ({@link HouseDoors});
 * until it says, none are.
 *
 * <p>Reads the LIVE world. The route was planned on a snapshot, and a door is exactly the block
 * that changes under a plan: somebody shuts it, somebody walks through it first.
 */
public final class Doorways {
    /** How near the body must be to a door before it swings it: a step short of the doorway. */
    private static final double REACH = 2.0;
    /** How far from a button or lever the body's eyes may be and still press it. */
    private static final double PRESS_REACH = 3.5;
    /**
     * How far a body gets from a door it went through before it gives up on it — about a player's
     * reach. A body that far off was carried or re-routed. Vanilla's villagers give up at three.
     */
    private static final double FORGET = 4.0;
    /**
     * How near the doorway another body must be for it to count as coming through: a door is not
     * shut in the face of somebody a step from it, who would only swing it open again.
     */
    private static final double COMING_THROUGH = 1.5;
    /**
     * How long a body that joined a door's crossing may go without saying it still is before the
     * others stop waiting for it — it died, was unloaded, or took another road.
     */
    private static final long STALE_TICKS = 100;

    /**
     * Whether a door is a house's — shut after passing, however it was found. The consuming mod
     * answers from what its people have built; Anima has no notion of a house.
     */
    @FunctionalInterface
    public interface HouseDoors {
        boolean isHouseDoor(ServerLevel level, BlockPos door);
    }

    private static volatile HouseDoors houseDoors = (level, door) -> false;

    /** Sets who answers {@link HouseDoors}. The last registration wins. */
    public static void houseDoors(HouseDoors rule) {
        houseDoors = rule;
    }

    /** A door this body went through, and how it was found before anybody swung it. */
    public record Passed(BlockPos pos, boolean foundOpen) {
    }

    /**
     * One door being gone through: how it was found, and who is still going through it. Shared by
     * every body in the level, so the last one through knows it is the last, and how to leave it.
     */
    private static final class Crossing {
        final boolean foundOpen;
        final Map<UUID, Long> users = new HashMap<>();

        Crossing(boolean foundOpen) {
            this.foundOpen = foundOpen;
        }
    }

    private static final Map<ServerLevel, Map<BlockPos, Crossing>> CROSSINGS = new WeakHashMap<>();

    private final AgentBody body;
    private final List<Passed> passed = new ArrayList<>(2);

    Doorways(AgentBody body) {
        this.body = body;
    }

    /**
     * One tick: ready what the route is about to go through, then settle what it has gone through.
     * Runs in every state — a body that arrived just inside a house still shuts the door behind it.
     *
     * @param path  the route being walked, or {@code null} when there is none to look ahead on
     * @param from  where the route was planned from, which the first waypoint's step leaves
     * @param hands whether this body may work a door at all
     */
    void tick(@Nullable Path path, int index, @Nullable BlockPos from, boolean hands) {
        ServerLevel level = (ServerLevel) this.body.level();
        if (hands && path != null) {
            openAhead(level, path, index, from);
        }
        leaveBehind(level, path, index);
    }

    /** The doors gone through and not yet settled, for a walk being saved. */
    List<Passed> held() {
        return List.copyOf(this.passed);
    }

    /** Puts back what a saved walk had yet to settle. */
    void restore(List<Passed> saved) {
        this.passed.clear();
        this.passed.addAll(saved);
    }

    /**
     * Readies every door and hatch on the steps either side of the one being walked to. A door gets
     * the state that lets the whole crossing through, in by one face and out by another; when no one
     * state does, the state to come in by, and the other once the body stands in the doorway.
     */
    private void openAhead(ServerLevel level, Path path, int index, @Nullable BlockPos from) {
        List<Waypoint> ways = path.waypoints();
        for (int j = Math.max(0, index - 1); j <= Math.min(index + 1, ways.size() - 1); j++) {
            Waypoint at = ways.get(j);
            int[] prev = j > 0 ? cellOf(ways.get(j - 1))
                    : from == null ? null : new int[] {from.getX(), from.getY(), from.getZ()};
            int[] next = j + 1 < ways.size() ? cellOf(ways.get(j + 1)) : null;
            boolean climbing = at.move() == MoveType.CLIMB
                    || (next != null && ways.get(j + 1).move() == MoveType.CLIMB);
            if (climbing) {
                hatchOnTheWay(level, at, next);
            }
            int in = prev == null ? 0 : face(prev, cellOf(at), true);
            int out = next == null ? 0 : face(cellOf(at), next, false);
            for (int cy = at.y(); cy <= at.y() + 1; cy++) {
                BlockPos cell = new BlockPos(at.x(), cy, at.z());
                if (!level.isLoaded(cell) || WorldSnapshot.classifyAt(level, cell) != CellType.DOOR) {
                    continue;
                }
                int code = WorldSnapshot.doorwayAt(level, cell);
                if (Doorway.swings(code) && withinReach(cell)) {
                    ready(level, cell, code, in, out, j < index);
                }
                break; // a door's halves move together
            }
        }
    }

    /**
     * Puts one door the way this crossing needs it: from outside, the state that lets it through in
     * and out if there is one, else the one it comes in by; from inside, the one it leaves by.
     */
    private void ready(ServerLevel level, BlockPos cell, int code, int in, int out, boolean inside) {
        join(level, cell);
        if (inside) {
            if (out != 0 && !Doorway.free(code, false, out) && Doorway.swingsInside(code, true)) {
                swing(level, cell);
            }
            return;
        }
        Boolean through = Doorway.crossingState(code, in, out);
        boolean wantSwung = through != null ? through : Doorway.entryState(code, in);
        if (!wantSwung) {
            return;
        }
        if (Doorway.byHand(code)) {
            swing(level, cell);
        } else if (in != 0) {
            press(level, cell, in);
        }
    }

    /**
     * Opens a hatch a climb goes through: up into it from the ladder under it, or down through it
     * from standing on it — a trapdoor shut in the top of a block is under the feet, one in the
     * bottom of a block is where they are.
     */
    private void hatchOnTheWay(ServerLevel level, Waypoint at, int @Nullable [] next) {
        BlockPos into = new BlockPos(at.x(), at.y(), at.z());
        // Going down, the hatch is where the feet are or under them; going up, where they are going
        // or where the head is — the top rung is a body's height under it.
        BlockPos[] candidates = next != null && next[1] < at.y()
                ? new BlockPos[] {into, into.below()}
                : new BlockPos[] {into, into.above()};
        for (BlockPos cell : candidates) {
            if (level.isLoaded(cell) && WorldSnapshot.hatchAt(level, cell) && withinReach(cell)) {
                join(level, cell);
                swing(level, cell);
                return;
            }
        }
    }

    private void swing(ServerLevel level, BlockPos cell) {
        LivingEntity entity = this.body.entity();
        if (Doors.swing(entity, level, cell)) {
            this.body.faceBlock(cell);
            Arms.swingToInteract(entity, InteractionHand.MAIN_HAND);
        }
    }

    /** Presses the button or pulls the lever that opens a door from outside its {@code face}. */
    private void press(ServerLevel level, BlockPos door, int face) {
        BlockState state = level.getBlockState(door);
        Direction side = directionOf(face);
        BlockPos activator = side == null ? null : Doors.activatorFor(level, door, state, side);
        if (activator == null) {
            return; // a pressure plate: stepping on it is what opens it
        }
        LivingEntity entity = this.body.entity();
        if (this.body.eyePosition().distanceTo(Vec3.atCenterOf(activator)) <= PRESS_REACH
                && Doors.press(entity, level, activator)) {
            this.body.faceBlock(activator);
            Arms.swingToInteract(entity, InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Settles every door this body went through once it is through: off the next steps of its route,
     * nobody in or at the doorway. The last body through leaves a house's door shut and any other
     * door the way it was found; one that is not the last leaves it to the others.
     */
    private void leaveBehind(ServerLevel level, @Nullable Path path, int index) {
        for (Iterator<Passed> it = this.passed.iterator(); it.hasNext(); ) {
            Passed door = it.next();
            BlockPos pos = door.pos();
            Boolean open = level.isLoaded(pos) ? Doors.isOpen(level, pos) : null;
            if (open == null || this.body.position().distanceTo(Vec3.atCenterOf(pos)) > FORGET) {
                leave(level, pos);
                it.remove();
                continue;
            }
            if (onRoute(pos, path, index) || occupied(level, pos)) {
                join(level, pos);
                continue;
            }
            Crossing crossing = leave(level, pos);
            it.remove();
            if (crossing != null && !crossing.users.isEmpty()) {
                continue; // somebody else is still going through: theirs to leave
            }
            boolean foundOpen = crossing != null ? crossing.foundOpen : door.foundOpen();
            boolean want = !houseDoors.isHouseDoor(level, pos) && foundOpen;
            if (open != want) {
                settle(level, pos, want);
            }
        }
    }

    /** Puts a door the other way by whatever works it: a hand, or the lever that opened it. */
    private void settle(ServerLevel level, BlockPos pos, boolean open) {
        int code = WorldSnapshot.classifyAt(level, pos) == CellType.DOOR
                ? WorldSnapshot.doorwayAt(level, pos) : -1;
        if (code == -1 || Doorway.byHand(code)) {
            swing(level, pos); // a hatch, a wooden door, a gate
            return;
        }
        // An iron door: a button or a plate lets go by itself; a lever stays where it was put.
        BlockState state = level.getBlockState(pos);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos lever = Doors.activatorFor(level, pos, state, side);
            if (lever != null && Doors.leverOn(level, lever) != null) {
                if (Doors.press(this.body.entity(), level, lever)) {
                    this.body.faceBlock(lever);
                    Arms.swingToInteract(this.body.entity(), InteractionHand.MAIN_HAND);
                }
                return;
            }
        }
    }

    /** Joins, or keeps up, this body's part in going through a door; remembers it to settle later. */
    private void join(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Crossing> crossings = CROSSINGS.computeIfAbsent(level, l -> new HashMap<>());
        Crossing crossing = crossings.get(pos);
        if (crossing == null) {
            Boolean open = Doors.isOpen(level, pos);
            crossing = new Crossing(open != null && open);
            crossings.put(pos.immutable(), crossing);
        }
        long now = level.getGameTime();
        crossing.users.put(this.body.entity().getUUID(), now);
        crossing.users.values().removeIf(seen -> now - seen > STALE_TICKS);
        if (this.passed.stream().noneMatch(door -> door.pos().equals(pos))) {
            this.passed.add(new Passed(pos.immutable(), crossing.foundOpen));
        }
    }

    /** This body is through a door; answers the crossing as it stands after, if it still exists. */
    private @Nullable Crossing leave(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Crossing> crossings = CROSSINGS.get(level);
        Crossing crossing = crossings == null ? null : crossings.get(pos);
        if (crossing == null) {
            return null;
        }
        long now = level.getGameTime();
        crossing.users.remove(this.body.entity().getUUID());
        crossing.users.values().removeIf(seen -> now - seen > STALE_TICKS);
        if (crossing.users.isEmpty()) {
            crossings.remove(pos);
        }
        return crossing;
    }

    private boolean withinReach(BlockPos cell) {
        Vec3 at = this.body.position();
        double dx = cell.getX() + 0.5 - at.x;
        double dz = cell.getZ() + 0.5 - at.z;
        return dx * dx + dz * dz <= REACH * REACH && Math.abs(cell.getY() - at.y) <= 2.0;
    }

    /** Whether the route is still about to use this doorway: its column is one of the next steps. */
    private static boolean onRoute(BlockPos door, @Nullable Path path, int index) {
        if (path == null) {
            return false;
        }
        List<Waypoint> ways = path.waypoints();
        for (int j = Math.max(0, index - 1); j <= Math.min(index + 1, ways.size() - 1); j++) {
            Waypoint at = ways.get(j);
            if (at.x() == door.getX() && at.z() == door.getZ()
                    && door.getY() >= at.y() - 1 && door.getY() <= at.y() + 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the doorway is in use: this body still in it, or anybody else in it or a step from it.
     */
    private boolean occupied(ServerLevel level, BlockPos door) {
        AABB doorway = new AABB(door).expandTowards(0.0, 1.0, 0.0);
        LivingEntity self = this.body.entity();
        if (self.getBoundingBox().intersects(doorway.inflate(0.1, 0.0, 0.1))) {
            return true;
        }
        return !level.getEntitiesOfClass(LivingEntity.class,
                doorway.inflate(COMING_THROUGH, 0.0, COMING_THROUGH),
                other -> other != self && other.isAlive()).isEmpty();
    }

    private static int[] cellOf(Waypoint w) {
        return new int[] {w.x(), w.y(), w.z()};
    }

    /**
     * The face of a doorway a straight step between two cells crosses: going {@code into} the second,
     * the face it comes in by; otherwise the face of the first it leaves by. 0 for a step that is not
     * one cardinal cell.
     */
    private static int face(int[] a, int[] b, boolean into) {
        int dx = Integer.signum(b[0] - a[0]);
        int dz = Integer.signum(b[2] - a[2]);
        if ((dx == 0) == (dz == 0)) {
            return 0;
        }
        int heading = NavGrid.heading(dx, dz);
        return into ? NavGrid.opposite(heading) : heading;
    }

    private static @Nullable Direction directionOf(int face) {
        return switch (face) {
            case NavGrid.NORTH -> Direction.NORTH;
            case NavGrid.SOUTH -> Direction.SOUTH;
            case NavGrid.WEST -> Direction.WEST;
            case NavGrid.EAST -> Direction.EAST;
            default -> null;
        };
    }
}
