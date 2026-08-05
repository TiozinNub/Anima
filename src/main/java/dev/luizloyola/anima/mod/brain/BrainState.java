package dev.luizloyola.anima.mod.brain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.task.CompoundTask;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.brain.task.TaskExecutor;
import dev.luizloyola.anima.core.brain.task.TaskStatus;
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
            Codec.DOUBLE.fieldOf("pressure").forGetter(Arbiter.Grant::activePressure),
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

    private static final Codec<Waypoint> WAYPOINT = RecordCodecBuilder.create(w -> w.group(
            Codec.INT.fieldOf("x").forGetter(Waypoint::x),
            Codec.INT.fieldOf("y").forGetter(Waypoint::y),
            Codec.INT.fieldOf("z").forGetter(Waypoint::z),
            Codec.STRING.fieldOf("move").forGetter(way -> way.move().name())
    ).apply(w, (x, y, z, move) -> new Waypoint(x, y, z, MoveType.valueOf(move))));

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
                    .forGetter(Navigator.Walk::proactiveRepathCooldown)
    ).apply(n, (state, goal, waypoints, reached, index, gait, stuck, noMove, grounded, lastLeap,
                repaths, integrity, cooldown) -> new Navigator.Walk(state, goal.orElse(null),
                    waypoints, reached, index, gait, stuck, noMove, grounded, lastLeap, repaths,
                    integrity, cooldown)));
}
