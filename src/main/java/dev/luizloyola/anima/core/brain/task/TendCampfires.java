package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.CampfireAccess;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.SmeltLookup;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Cook from beside a campfire: put what is to be cooked on every free slot of the lit campfires
 * around it ({@link Campfire#hearth}), stay, pick up what they drop, and put more on until all of
 * it is cooked (directions spec, decision 26). One item a handling pause, as a furnace is loaded.
 *
 * <p>What drops is walked to, cell by cell, or stood beside when it lies on a fire. SUCCEEDS when
 * something cooked came back to the pack; FAILS with no campfire in reach, nothing a campfire
 * cooks, or nothing back.
 */
public final class TendCampfires implements PrimitiveTask {

    /** Past the longest cook with nothing put on and nothing picked up, the cook gives up. */
    static final int SLACK = 200;

    private static final int HEARTH_AGE = 40;

    /** How far from a fire what it cooked is looked for: a steak rolled three blocks off (2026-10-01). */
    private static final double ROLL = 4.0;

    /** Ticks a drop is left to land and be picked up before the cook steps for it. */
    private static final int SETTLE = 20;

    private final Pos at;
    private final ItemSpec raw;
    private final int count;

    private int placed;
    private int idle;
    /** What the pack held of the cooked forms when it started; -1 before the first tick. */
    private int cookedAtStart = -1;
    private int cookedNow;
    private List<String> outputs = List.of();
    private int limit;
    private final Pause pause = new Pause();

    // Transient: a walk is issued again after a reload, the sides tried only steer the next step,
    // and the journal's tallies only word lines.
    private boolean walking;
    private final Map<String, Integer> round = new LinkedHashMap<>();
    private final Map<String, Integer> held = new LinkedHashMap<>();
    private int pickedUp;
    private final Set<Pos> tried = new HashSet<>();
    private @Nullable Pos chasing;
    private int settle;
    private @Nullable List<Pos> hearth;
    private int hearthAge;
    private @Nullable String failure;

    public TendCampfires(Pos at, ItemSpec raw, int count) {
        this.at = at;
        this.raw = raw;
        this.count = Math.max(1, count);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + 0.5, at.z() + 0.5, Gazer.Priority.WORK);
        if (cookedAtStart < 0 && !begin(ctx)) {
            return TaskStatus.FAILED;
        }
        noticePickups(ctx);
        // Every tick that neither puts one on nor picks one up counts, walking too: a walk that
        // keeps failing must end the job as surely as a fire that never gives anything back.
        if (++idle > limit) {
            return finish(ctx);
        }
        if (walking) {
            if (ctx.actuators().mover().state() == MoveState.MOVING) {
                return TaskStatus.RUNNING;
            }
            walking = false;
            if (chasing != null) {
                tried.add(chasing);
                chasing = null;
            }
            settle = 0;
        }
        CampfireAccess fires = ctx.actuators().campfires();
        if (!pause.idle()) {
            if (pause.elapsed()) {
                putOne(ctx, fires);
            }
            return TaskStatus.RUNNING;
        }
        if (Workbench.distance(ctx.percepts().position(), at) > Campfire.REACH - 1.0) {
            return walk(ctx, EnsureTable.WalkToKnown.standableBeside(at, ctx));
        }
        if (fires.read(at).isEmpty()) {
            // Beside it and finding none, the body corrects the record, as at a furnace.
            ctx.knowledge().disprove(Campfire.POI, at);
            failure = "no campfire to reach";
            return TaskStatus.FAILED;
        }
        List<Pos> hearth = hearth(ctx);
        if (left(ctx) > 0 && freeLit(fires, hearth) != null) {
            pause.start(ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS));
            if (pause.elapsed()) {
                putOne(ctx, fires);
            }
            return TaskStatus.RUNNING;
        }
        sayPut(ctx);
        Pos toward = ++settle >= SETTLE ? dropTarget(ctx, hearth) : null;
        if (toward != null) {
            chasing = toward;
            return walk(ctx, toward);
        }
        if (busy(fires, hearth) || dropNear(ctx, hearth)) {
            return TaskStatus.RUNNING;
        }
        return finish(ctx);
    }

    private TaskStatus finish(BrainContext ctx) {
        ctx.actuators().mover().stop();
        sayPut(ctx);
        sayPicked(ctx);
        if (cookedNow > cookedAtStart) {
            return TaskStatus.SUCCESS;
        }
        failure = placed == 0 ? "no lit campfire with room" : "nothing came back from the campfire";
        return TaskStatus.FAILED;
    }

    /** The fires worked, looked for again now and then: one can be put down or put out meanwhile. */
    private List<Pos> hearth(BrainContext ctx) {
        if (hearth == null || ++hearthAge >= HEARTH_AGE) {
            // The nearest that burn: an unlit one cooks nothing and would take a lit one's place.
            List<Pos> lit = new ArrayList<>();
            for (Pos fire : Campfire.hearth(ctx, at)) {
                if (lit.size() < Campfire.WORKED && ctx.actuators().campfires().read(fire)
                        .map(CampfireAccess.View::lit).orElse(false)) {
                    lit.add(fire);
                }
            }
            hearth = lit;
            hearthAge = 0;
        }
        return hearth;
    }

    /** What the pack holds to cook decides what to watch for and how long a round can take. */
    private boolean begin(BrainContext ctx) {
        SmeltLookup lookup = ctx.percepts().smelting();
        List<String> made = new ArrayList<>();
        int longest = 0;
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            String id = entry.stack().id();
            if (!raw.matches(id)) {
                continue;
            }
            Optional<SmeltLookup.Smelt> cook = lookup.campfire(id);
            if (cook.isPresent()) {
                if (!made.contains(cook.get().outputId())) {
                    made.add(cook.get().outputId());
                }
                longest = Math.max(longest, cook.get().ticks());
            }
        }
        if (made.isEmpty()) {
            failure = "nothing to cook on a campfire";
            return false;
        }
        outputs = List.copyOf(made);
        limit = longest + SLACK;
        cookedAtStart = cooked(ctx);
        cookedNow = cookedAtStart;
        return true;
    }

    private int cooked(BrainContext ctx) {
        int n = 0;
        for (String id : outputs) {
            n += ctx.percepts().inventory().count(id);
        }
        return n;
    }

    private void noticePickups(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        for (String id : outputs) {
            int now = pack.count(id);
            Integer before = held.put(id, now);
            if (before != null && now > before) {
                pickedUp += now - before;
                tried.clear();
                settle = 0;
            }
        }
        int now = cooked(ctx);
        if (now > cookedNow) {
            idle = 0;
        }
        cookedNow = now;
    }

    /** How many more go on: what is left of the count, as far as the pack holds them. */
    private int left(BrainContext ctx) {
        return Math.min(count - placed, ctx.percepts().inventory().count(id -> raw.matches(id) && cooks(ctx, id)));
    }

    private static boolean cooks(BrainContext ctx, String id) {
        return ctx.percepts().smelting().campfire(id).isPresent();
    }

    private static @Nullable Pos freeLit(CampfireAccess fires, List<Pos> hearth) {
        for (Pos fire : hearth) {
            Optional<CampfireAccess.View> view = fires.read(fire);
            if (view.isPresent() && view.get().lit() && view.get().free() > 0) {
                return fire;
            }
        }
        return null;
    }

    private void putOne(BrainContext ctx, CampfireAccess fires) {
        Pos fire = freeLit(fires, hearth(ctx));
        if (fire == null || left(ctx) <= 0) {
            return;
        }
        Inventory pack = ctx.percepts().inventory();
        // The kind held most of, as a furnace slot is loaded.
        String id = null;
        for (Inventory.Entry entry : pack.occupied()) {
            String kind = entry.stack().id();
            if (raw.matches(kind) && cooks(ctx, kind) && (id == null || pack.count(kind) > pack.count(id))) {
                id = kind;
            }
        }
        if (id == null) {
            return;
        }
        dev.luizloyola.anima.core.inv.ItemStack sample = null;
        for (Inventory.Entry entry : pack.occupied()) {
            if (entry.stack().id().equals(id)) {
                sample = entry.stack();
                break;
            }
        }
        if (sample == null || !fires.place(fire, sample.withCount(1))) {
            return;
        }
        pack.remove(id, 1);
        placed++;
        idle = 0;
        round.merge(id, 1, Integer::sum);
    }

    /** One line for what came back since the last round, then one for each kind put on. */
    private void sayPut(BrainContext ctx) {
        if (round.isEmpty()) {
            return;
        }
        sayPicked(ctx);
        for (Map.Entry<String, Integer> put : round.entrySet()) {
            ctx.journal().record(Category.BRAIN, "campfire",
                    "put " + put.getValue() + "×" + put.getKey() + " on the campfires");
        }
        round.clear();
    }

    private void sayPicked(BrainContext ctx) {
        if (pickedUp > 0) {
            ctx.journal().record(Category.BRAIN, "campfire", "picked up " + pickedUp + " cooked");
            pickedUp = 0;
        }
    }

    private static boolean busy(CampfireAccess fires, List<Pos> hearth) {
        for (Pos fire : hearth) {
            Optional<CampfireAccess.View> view = fires.read(fire);
            if (view.isPresent() && view.get().lit() && view.get().cooking()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where to stand next for a cooked drop by the hearth: the drop's own cell, or a side of the fire
     * it lies on, nearest first. Not where the body stands, nor anywhere it already stood since the
     * last pickup — a steak on a fire's far rim is out of reach from the near side for good, so the
     * next side is tried rather than the same one again (2026-10-01).
     */
    private @Nullable Pos dropTarget(BrainContext ctx, List<Pos> hearth) {
        Pos here = ctx.percepts().position();
        Pos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Drop drop : ours(ctx, hearth)) {
            for (Pos stand : standsFor(ctx, drop.pos(), hearth)) {
                double distance = Workbench.distance(here, stand);
                if (!stand.equals(here) && !tried.contains(stand) && distance < bestDistance) {
                    best = stand;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** The drop's cell, or the open sides of the fire it rests on — its cell, or the one above. */
    private static List<Pos> standsFor(BrainContext ctx, Pos drop, List<Pos> hearth) {
        Pos below = new Pos(drop.x(), drop.y() - 1, drop.z());
        Pos fire = hearth.contains(drop) ? drop : hearth.contains(below) ? below : null;
        if (fire == null) {
            return List.of(drop);
        }
        List<Pos> sides = new ArrayList<>(4);
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos cell = new Pos(fire.x() + side[0], fire.y(), fire.z() + side[1]);
            if (ctx.percepts().blocks().at(cell.x(), cell.y(), cell.z()) == BlockKind.AIR) {
                sides.add(cell);
            }
        }
        return sides;
    }

    private boolean dropNear(BrainContext ctx, List<Pos> hearth) {
        return !ours(ctx, hearth).isEmpty();
    }

    private List<Drop> ours(BrainContext ctx, List<Pos> hearth) {
        List<Drop> out = new ArrayList<>();
        for (Drop drop : ctx.percepts().drops()) {
            if (!outputs.contains(drop.itemId()) || !Flocks.gatherable(drop, ctx)) {
                continue;
            }
            for (Pos fire : hearth) {
                if (Workbench.distance(drop.pos(), fire) <= ROLL) {
                    out.add(drop);
                    break;
                }
            }
        }
        return out;
    }

    private TaskStatus walk(BrainContext ctx, Pos to) {
        ctx.actuators().mover().moveTo(to.x(), to.y(), to.z());
        walking = true;
        return TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().mover().stop();
    }

    @Override
    public String describe() {
        return "cook " + raw.name() + " on the campfire";
    }

    @Override
    public String failureDetail() {
        return failure == null ? describe() + " failed" : failure;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public Pos at() {
        return at;
    }

    public ItemSpec raw() {
        return raw;
    }

    public int count() {
        return count;
    }

    public int placed() {
        return placed;
    }

    public int idle() {
        return idle;
    }

    public int cookedAtStart() {
        return cookedAtStart;
    }

    public List<String> outputs() {
        return outputs;
    }

    public int limit() {
        return limit;
    }

    public int pauseTicks() {
        return pause.remaining();
    }

    public TendCampfires resume(int placed, int idle, int cookedAtStart, List<String> outputs, int limit,
                                int pauseTicks) {
        this.placed = placed;
        this.idle = idle;
        this.cookedAtStart = cookedAtStart;
        this.cookedNow = Math.max(0, cookedAtStart);
        this.outputs = List.copyOf(outputs);
        this.limit = limit;
        this.pause.restore(pauseTicks);
        return this;
    }
}
