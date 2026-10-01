package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.nav.WalkLevel;
import java.util.ArrayList;
import java.util.List;

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
 */
public final class GatherNearbyDrops implements PrimitiveTask {
    private final ItemSpec spec;
    private final boolean nearWork;

    private int startCount = -1;
    private int laps;
    private int lapCap = -1;
    private boolean walkIssued;

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
        List<Pos> matching = new ArrayList<>();
        for (Drop drop : ctx.percepts().drops()) {
            if (wanted(drop, spec, nearWork, ctx)) {
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
        Pos centroid = Flocks.nearestCentroid(matching, ctx.percepts().position());
        ctx.actuators().mover().moveTo(centroid.x(), centroid.y(), centroid.z(), Gait.WALK,
                WalkLevel.SCALE.underWork(ctx.walksMayBuild()));
        walkIssued = true;
        laps++;
        return TaskStatus.RUNNING;
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
    // Four fields, and every one of them is a lap counter of some sort: this task walks a circuit
    // and gives up after so many. Losing them hands a body an unbounded errand.

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

    public GatherNearbyDrops resume(int startCount, int laps, int lapCap, boolean walkIssued) {
        this.startCount = startCount;
        this.laps = laps;
        this.lapCap = lapCap;
        this.walkIssued = walkIssued;
        return this;
    }
}
