package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.Entry;
import java.util.List;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.ClaimIndex;
import dev.luizloyola.anima.core.brain.knowledge.Column;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiSensorCore;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Setbacks;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Waypoint;
import dev.luizloyola.anima.mod.nav.Navigator;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.Optional;

/**
 * Codecs for the parts of a brain a body carries across a reload, supplied by Anima so every
 * consumer spells them the same way.
 *
 * <p>The rule (decision: Luiz, 2026-08-05): only what a tick recomputes from the senses may be
 * forgotten; anything outliving the tick that made it has to survive.
 */
public final class BrainState {

    private BrainState() {
    }

    /** Drives sitting out a fail-cooldown, by {@code Instinct.key()}, with the ticks they have left. */
    public static final Codec<Map<String, Integer>> COOLDOWNS =
            Codec.unboundedMap(Codec.STRING, Codec.INT);

    /** How a task's ending round-trips. An unknown one errors rather than defaulting to RUNNING,
     *  which would restart a finished plan. */
    private static final Codec<TaskStatus> STATUS = Codec.STRING.comapFlatMap(
            name -> {
                try {
                    return DataResult.success(TaskStatus.valueOf(name));
                } catch (IllegalArgumentException e) {
                    return DataResult.error(() -> "no task status called \"" + name + "\"");
                }
            },
            TaskStatus::name);

    /**
     * One suspended frame. The compound and its subtasks go through the task registry; the method
     * is an index into {@code compound.methods()}, which is rebuilt on load — that list takes no
     * context and draws nothing, so rebuilding it is free and identical.
     */
    private static Codec<TaskExecutor.FrameState> frame() {
        Codec<Task> task = TaskCodecs.codec();
        return RecordCodecBuilder.create(f -> f.group(
                task.fieldOf("compound").forGetter(
                        state -> (Task) state.compound()),
                Codec.INT.fieldOf("method").forGetter(TaskExecutor.FrameState::methodIndex),
                Codec.BOOL.listOf().fieldOf("tried").forGetter(TaskExecutor.FrameState::tried),
                task.listOf().fieldOf("subtasks").forGetter(TaskExecutor.FrameState::subtasks),
                Codec.INT.fieldOf("index").forGetter(TaskExecutor.FrameState::index),
                Codec.INT.fieldOf("rounds").forGetter(TaskExecutor.FrameState::rounds),
                Codec.DOUBLE.fieldOf("lastProgress")
                        .forGetter(TaskExecutor.FrameState::lastProgress),
                Codec.INT.fieldOf("pricedOut").forGetter(TaskExecutor.FrameState::pricedOut)
        ).apply(f, (compound, method, tried, subtasks, index, rounds, lastProgress, pricedOut) ->
                new TaskExecutor.FrameState((CompoundTask) compound, method, tried, subtasks,
                        index, rounds, lastProgress, pricedOut)));
    }

    /**
     * The whole plan, mid-descent. Built on demand, not held in a static field: the task registry
     * fills during mod init, and a codec captured at class-load would freeze an empty one.
     */
    public static Codec<TaskExecutor.State> executor() {
        Codec<Task> task = TaskCodecs.codec();
        return RecordCodecBuilder.create(s -> s.group(
                task.optionalFieldOf("root").forGetter(
                        state -> Optional.ofNullable(state.root())),
                frame().listOf().fieldOf("frames").forGetter(TaskExecutor.State::frames),
                Codec.STRING.optionalFieldOf("describe").forGetter(
                        state -> Optional.ofNullable(state.lastDescription())),
                STATUS.optionalFieldOf("status").forGetter(
                        state -> Optional.ofNullable(state.lastStatus())),
                Codec.STRING.optionalFieldOf("why").forGetter(
                        state -> Optional.ofNullable(state.failureReason()))
        ).apply(s, (root, frames, describe, status, why) -> new TaskExecutor.State(
                root.orElse(null), frames, describe.orElse(null), status.orElse(null),
                why.orElse(null))));
    }

    private static final Codec<Arbiter.Grant> GRANT = RecordCodecBuilder.create(g -> g.group(
            Codec.STRING.fieldOf("active").forGetter(Arbiter.Grant::active),
            Codec.BOOL.fieldOf("working").forGetter(Arbiter.Grant::workRunning),
            Codec.STRING.fieldOf("lastGranted").forGetter(Arbiter.Grant::lastGranted)
    ).apply(g, Arbiter.Grant::new));

    /**
     * A plan and the grant that owns it, as one field on purpose: saved separately, a future edit
     * could save one and forget the other, leaving an arbiter that believes a drive is mid-errand
     * while the executor sits empty, or an executor running a plan nothing granted.
     */
    public static Codec<BrainDriver.BrainSnapshot> brain() {
        return RecordCodecBuilder.create(b -> b.group(
                executor().fieldOf("plan").forGetter(BrainDriver.BrainSnapshot::plan),
                GRANT.fieldOf("grant").forGetter(BrainDriver.BrainSnapshot::grant)
        ).apply(b, BrainDriver.BrainSnapshot::new));
    }

    /**
     * {@code surface} is optional, not required: a walk saved before partial floors existed has no
     * such field, and vanilla parses with {@code resultOrPartial}, so a required field would drop
     * every waypoint of every stored path and still load "successfully". The default means feet on
     * the floor of their cell.
     */
    private static final Codec<Waypoint> WAYPOINT = RecordCodecBuilder.create(w -> w.group(
            Codec.INT.fieldOf("x").forGetter(Waypoint::x),
            Codec.INT.fieldOf("y").forGetter(Waypoint::y),
            Codec.INT.fieldOf("z").forGetter(Waypoint::z),
            Codec.STRING.fieldOf("move").forGetter(way -> way.move().name()),
            Codec.INT.optionalFieldOf("surface", 0).forGetter(Waypoint::surface16)
    ).apply(w, (x, y, z, move, surface) -> new Waypoint(x, y, z, MoveType.valueOf(move), surface)));

    /**
     * A walk in progress. The route is carried rather than re-pathed: paths of similar cost are
     * chosen among, so asking again gives <em>a</em> route rather than <em>the</em> route, and the
     * waypoint index and stuck counters only mean anything against the route they were counted on.
     */
    public static final Codec<Navigator.Walk> WALK = RecordCodecBuilder.create(n -> n.group(
            Codec.STRING.fieldOf("state").forGetter(Navigator.Walk::state),
            BlockPos.CODEC.optionalFieldOf("goal").forGetter(
                    walk -> Optional.ofNullable(walk.goal())),
            WAYPOINT.listOf().fieldOf("waypoints").forGetter(Navigator.Walk::waypoints),
            Codec.BOOL.fieldOf("reached").forGetter(Navigator.Walk::reachedGoal),
            Codec.INT.fieldOf("index").forGetter(Navigator.Walk::index),
            Codec.STRING.fieldOf("gait").forGetter(Navigator.Walk::gait),
            Codec.INT.fieldOf("stuck").forGetter(Navigator.Walk::stuckTicks),
            Codec.INT.fieldOf("noMove").forGetter(Navigator.Walk::noMoveTicks),
            Codec.INT.fieldOf("grounded").forGetter(Navigator.Walk::groundedTicks),
            Codec.INT.fieldOf("lastLeap").forGetter(Navigator.Walk::lastLeapPressIndex),
            Codec.INT.fieldOf("repaths").forGetter(Navigator.Walk::repathsLeft),
            Codec.INT.fieldOf("integrity").forGetter(Navigator.Walk::integrityCheckedIndex),
            Codec.INT.fieldOf("repathCooldown")
                    .forGetter(Navigator.Walk::proactiveRepathCooldown),
            // Optional with a none default so walks saved before the reason channel existed load
            // as what they actually were: a failure nobody had recorded a cause for.
            Codec.STRING.optionalFieldOf("failure", MoveFailure.NONE.name())
                    .forGetter(Navigator.Walk::failure)
    ).apply(n, (state, goal, waypoints, reached, index, gait, stuck, noMove, grounded, lastLeap,
                repaths, integrity, cooldown, failure) -> new Navigator.Walk(state,
                    goal.orElse(null), waypoints, reached, index, gait, stuck, noMove, grounded,
                    lastLeap, repaths, integrity, cooldown, failure)));

    /** One journal line. Categories round-trip by name; an unknown one errors rather than
     *  silently re-filing a line under the wrong subsystem. */
    private static final Codec<Entry> ENTRY = RecordCodecBuilder.create(e -> e.group(
            Codec.LONG.fieldOf("tick").forGetter(Entry::tick),
            Codec.STRING.fieldOf("cat").forGetter(entry -> entry.category().name()),
            Codec.STRING.fieldOf("event").forGetter(Entry::event),
            Codec.STRING.fieldOf("detail").forGetter(Entry::detail)
    ).apply(e, (tick, cat, event, detail) ->
            new Entry(tick, Category.valueOf(cat), event, detail)));

    /** A body's own account of itself — see {@code JournalService#snapshot}. */
    public static final Codec<List<Entry>> JOURNAL = ENTRY.listOf();

    private static final Codec<BlockPos> BLOCK_POS = BlockPos.CODEC;

    /**
     * A swing in progress. Paired with the task flag that says one is under way — a body that came
     * back "breaking" with a breaker at rest waits on a swing nobody is swinging.
     */
    public static final Codec<AgentBlockBreaker.Swing> SWING =
            RecordCodecBuilder.create(b -> b.group(
                    Codec.STRING.fieldOf("state").forGetter(AgentBlockBreaker.Swing::state),
                    BLOCK_POS.optionalFieldOf("target")
                            .forGetter(sw -> Optional.ofNullable(sw.target())),
                    Codec.FLOAT.fieldOf("progress").forGetter(AgentBlockBreaker.Swing::progress),
                    Codec.INT.fieldOf("stage").forGetter(AgentBlockBreaker.Swing::sentStage)
            ).apply(b, (state, target, progress, stage) ->
                    new AgentBlockBreaker.Swing(state, target.orElse(null), progress, stage)));

    /** A step in progress, and where the last one died. Paired the same way. */
    public static final Codec<AgentRiser.Step> STEP = RecordCodecBuilder.create(r -> r.group(
            Codec.STRING.fieldOf("state").forGetter(AgentRiser.Step::state),
            BLOCK_POS.optionalFieldOf("base").forGetter(st -> Optional.ofNullable(st.base())),
            Codec.STRING.optionalFieldOf("item").forGetter(st -> Optional.ofNullable(st.itemId())),
            Codec.INT.fieldOf("ticks").forGetter(AgentRiser.Step::ticks),
            Codec.INT.fieldOf("centringTicks").forGetter(AgentRiser.Step::centringTicks),
            Codec.BOOL.fieldOf("centring").forGetter(AgentRiser.Step::centring),
            BLOCK_POS.optionalFieldOf("failedCell")
                    .forGetter(st -> Optional.ofNullable(st.failedCell())),
            Codec.INT.fieldOf("failedStreak").forGetter(AgentRiser.Step::failedStreak)
    ).apply(r, (state, base, item, ticks, centringTicks, centring, failedCell, failedStreak) ->
            new AgentRiser.Step(state, base.orElse(null), item.orElse(null), ticks, centringTicks,
                    centring, failedCell.orElse(null), failedStreak)));

    private static final Codec<Pos> SENSE_POS = RecordCodecBuilder.create(p -> p.group(
            Codec.INT.fieldOf("x").forGetter(Pos::x),
            Codec.INT.fieldOf("y").forGetter(Pos::y),
            Codec.INT.fieldOf("z").forGetter(Pos::z)
    ).apply(p, Pos::new));

    /**
     * Where a body has lately been beaten.
     *
     * <p>The tick is ABSOLUTE, not an age: entries fade from when they happened rather than
     * getting a fresh lease from the save, so a world left overnight comes back already expired.
     *
     * <p>Kinds round-trip by name; an unknown one errors rather than silently re-filing, as the
     * journal's category codec does.
     */
    public static final Codec<List<Setbacks.Setback>> SETBACKS = RecordCodecBuilder
            .<Setbacks.Setback>create(s -> s.group(
                    SENSE_POS.fieldOf("at").forGetter(Setbacks.Setback::at),
                    Codec.STRING.fieldOf("kind").forGetter(entry -> entry.kind().name()),
                    Codec.LONG.fieldOf("tick").forGetter(Setbacks.Setback::tick),
                    Codec.INT.fieldOf("strength").forGetter(Setbacks.Setback::strength)
            ).apply(s, (at, kind, tick, strength) ->
                    new Setbacks.Setback(at, Setbacks.Kind.valueOf(kind), tick, strength)))
            .listOf();

    private static final Codec<ClaimIndex.Claim> CLAIM = RecordCodecBuilder.create(c -> c.group(
            Codec.STRING.fieldOf("kind").forGetter(claim -> claim.kind().key()),
            SENSE_POS.optionalFieldOf("anchor")
                    .forGetter(claim -> Optional.ofNullable(claim.anchor())),
            Codec.STRING.fieldOf("expected").forGetter(claim -> claim.expected().key())
    ).apply(c, (kind, anchor, expected) -> new ClaimIndex.Claim(
            PoiKind.byKey(kind).orElseThrow(() -> new IllegalStateException("no poi kind " + kind)),
            anchor.orElse(null),
            BlockKind.byKey(expected).orElseThrow(
                    () -> new IllegalStateException("no block kind " + expected)))));

    /**
     * What a body has already surveyed. Not correctness — an unsaved index re-derives the same
     * answers — but a settler re-walking ground it accounted for days ago is time it can feel.
     */
    public static final Codec<PoiSensorCore.State> SURVEY = RecordCodecBuilder.create(s -> s.group(
            RecordCodecBuilder.<ClaimIndex.Entry>create(e -> e.group(
                    SENSE_POS.fieldOf("at").forGetter(ClaimIndex.Entry::at),
                    CLAIM.fieldOf("claim").forGetter(ClaimIndex.Entry::claim)
            ).apply(e, ClaimIndex.Entry::new)).listOf().fieldOf("claims")
                    .forGetter(PoiSensorCore.State::claims),
            RecordCodecBuilder.<Column>create(c -> c.group(
                    Codec.INT.fieldOf("x").forGetter(Column::x),
                    Codec.INT.fieldOf("z").forGetter(Column::z)
            ).apply(c, Column::new)).listOf().fieldOf("pending")
                    .forGetter(PoiSensorCore.State::pending)
    ).apply(s, PoiSensorCore.State::new));
}
