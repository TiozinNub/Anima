package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.nav.WalkLevel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sweep every sighted drop matching a spec: walk the nearest flock centroid ({@link Flocks}), let
 * the walk-over pickup vacuum the path, re-scan, repeat until nothing matching is in sight. The
 * sight radius is the work area — no bounds parameter; scope the spec instead.
 *
 * <p>Asked {@link #nearWork}, it takes only drops lying near this body's own recent work
 * ({@link dev.luizloyola.anima.core.brain.history.WorkSpots}): what its felling, building and
 * killing let fall, and nobody else's.
 *
 * <p>SUCCESS only when {@code count(spec)} rose since the first tick. Empty-handed — drops gone, or
 * the lap guard tripped on unreachable ones — is FAILED, which lets an {@code ObtainItem} burn its
 * pickup method and move on to producing.
 *
 * <p>A flock the legs found no way to is struck from the sweep: a drop up in a tree crown cost nine
 * searches in nine ticks, one per lap (2026-10-01). It is struck for the body too
 * ({@link dev.luizloyola.anima.core.brain.history.Unreached}), or the next sweep an obtain starts
 * walks for it again: thirty-two rounds for dirt across a gap (2026-10-02).
 */
public final class GatherNearbyDrops implements PrimitiveTask {
    private final ItemSpec spec;
    private final boolean nearWork;

    private int startCount = -1;
    private int laps;
    private int lapCap = -1;
    private boolean walkIssued;
    /** The flock the last walk was ordered to, and the drops struck from this sweep. */
    private List<Pos> aimed = List.of();
    private final Set<Pos> struck = new LinkedHashSet<>();

    public GatherNearbyDrops(ItemSpec spec) {
        this(spec, false);
    }

    public GatherNearbyDrops(ItemSpec spec, boolean nearWork) {
        this.spec = spec;
        this.nearWork = nearWork;
    }

    /** Whether {@code drop} is one this sweep takes: of the spec, standing, and near work if asked. */
    public static boolean wanted(Drop drop, ItemSpec spec, boolean nearWork, BrainContext ctx) {
        return spec.matches(drop.itemId()) && Flocks.gatherable(drop, ctx)
                && (!nearWork || ctx.workSpots().near(drop.pos(), ctx.percepts().time()));
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (startCount < 0) {
            startCount = ctx.percepts().inventory().count(spec.matcher());
        }
        if (walkIssued && ctx.actuators().mover().state() == MoveState.FAILED
                && unwalkable(ctx.actuators().mover().failure())) {
            struck.addAll(aimed);
            for (Pos drop : aimed) {
                ctx.unreached().strike(drop, ctx.percepts().time());
            }
            aimed = List.of();
        }
        List<Pos> matching = new ArrayList<>();
        for (Drop drop : ctx.percepts().drops()) {
            if (!struck.contains(drop.pos()) && wanted(drop, spec, nearWork, ctx)) {
                matching.add(drop.pos());
            }
        }
        if (lapCap < 0) {
            lapCap = Flocks.count(matching) * 3 + 6;
        }
        if (matching.isEmpty() || laps >= lapCap) {
            ctx.actuators().mover().stop();
            boolean gathered = ctx.percepts().inventory().count(spec.matcher()) > startCount;
            return gathered ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }
        if (walkIssued && ctx.actuators().mover().state() == MoveState.MOVING) {
            return TaskStatus.RUNNING;
        }
        aimed = List.copyOf(Flocks.nearestFlock(matching, ctx.percepts().position()));
        Pos centroid = Standing.floorUnder(ctx, Flocks.centroid(aimed)); // an average y, or a drop still falling
        ctx.actuators().mover().moveTo(centroid.x(), centroid.y(), centroid.z(), Gait.WALK,
                WalkLevel.SCALE.underWork(ctx.walksMayBuild()));
        walkIssued = true;
        laps++;
        return TaskStatus.RUNNING;
    }

    /** The legs' verdict that no way leads there, as opposed to a walk that went wrong on the way. */
    private static boolean unwalkable(MoveFailure failure) {
        return failure == MoveFailure.STRANDED || failure == MoveFailure.UNREACHABLE;
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().mover().stop();
    }

    @Override
    public String describe() {
        return "gather " + spec.name() + (nearWork ? " near my work" : "");
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────
    // Lap counters and strikes: this task walks a circuit and gives up after so many, and skips
    // what it could not walk to. Losing them hands a body an unbounded errand.

    public ItemSpec spec() {
        return spec;
    }

    public boolean nearWork() {
        return nearWork;
    }

    public int startCount() {
        return startCount;
    }

    public int laps() {
        return laps;
    }

    public int lapCap() {
        return lapCap;
    }

    public boolean walkIssued() {
        return walkIssued;
    }

    public List<Pos> aimed() {
        return aimed;
    }

    public List<Pos> struck() {
        return List.copyOf(struck);
    }

    public GatherNearbyDrops resume(int startCount, int laps, int lapCap, boolean walkIssued,
            List<Pos> aimed, List<Pos> struck) {
        this.startCount = startCount;
        this.laps = laps;
        this.lapCap = lapCap;
        this.walkIssued = walkIssued;
        this.aimed = List.copyOf(aimed);
        this.struck.clear();
        this.struck.addAll(struck);
        return this;
    }
}
