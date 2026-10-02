package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.WalkLevel;
import org.jspecify.annotations.Nullable;

/**
 * The movement actuator port: core defines the interface in terms of what an NPC needs ("walk to
 * that cell") and the mod layer implements it over the Navigator. The only way a task moves the
 * body; nothing in {@code core} ever sees the Navigator itself.
 *
 * <p>Primitive tasks call it from {@code TaskExecutor.tick}, which the mod {@code BrainDriver} runs
 * from {@code serverAiStep} before the Navigator ticks — so a {@link #moveTo} issued this tick is
 * acted on this same tick, with no dead tick in between.
 */
public interface Mover {
    /**
     * Begin navigating to the given cell at the ordinary pace — shorthand for
     * {@link #moveTo(int, int, int, Gait)} with {@link Gait#WALK}.
     */
    default void moveTo(int x, int y, int z) {
        moveTo(x, y, z, Gait.WALK);
    }

    /**
     * Begin navigating to the given cell, replacing any move already in progress — the newest
     * order always wins, there is no queue. From the next tick on, {@link #state()} reports this
     * order's progress.
     *
     * @param gait the requested pace (see {@link Gait}): a flee leg orders {@link Gait#SPRINT},
     *             a wander leg {@link Gait#STROLL}, everything else {@link Gait#WALK}. The mod's
     *             Navigator decides where each gait actually applies (terrain overrides mood);
     *             the port stays advisory, never a guarantee.
     */
    default void moveTo(int x, int y, int z, Gait gait) {
        moveTo(x, y, z, gait, WalkLevel.of(gait));
    }

    /**
     * As {@link #moveTo(int, int, int, Gait)}, saying what the walk may do to the ground it
     * crosses — see {@link WalkLevel}. Unsaid, a plain walk may scale a soft step and a stroll or a
     * sprint may not.
     */
    void moveTo(int x, int y, int z, Gait gait, WalkLevel level);

    /** Progress of the most recent order; {@link MoveState#IDLE} when there is none. */
    MoveState state();

    /**
     * Why the most recent order ended badly — meaningful only while {@link #state()} reads
     * {@link MoveState#FAILED}, and {@link MoveFailure#NONE} at every other moment.
     *
     * <p>Split off {@link MoveState} because the two are read by different questions at different
     * rates: "is my order still being worked on" every tick, "why did it die" once, by the few
     * callers that can act on the answer. The default keeps every existing implementation correct.
     */
    default MoveFailure failure() {
        return MoveFailure.NONE;
    }

    /**
     * With {@link MoveFailure#STRANDED} on a walk that may build: how many blocks in hand would have
     * got it there, more than it carried. Zero at every other moment, and from a mover that cannot
     * say.
     */
    default int blocksNeeded() {
        return 0;
    }

    /**
     * Whether the legs are still WAITING ON A ROUTE rather than walking one — the difference
     * {@link MoveState} hides.
     *
     * <p><b>A wait denominated in ticks must not spend its budget while this is true.</b>
     * Route-finding runs off-thread and costs milliseconds; walking is paid in ticks, so a tick
     * budget mixes units. Raise the server's tick rate and the budget shrinks in real time while
     * the search takes exactly as long: errands that succeed at 20 tps fail as unreachable at 200,
     * with the bodies standing in front of perfectly reachable work (observed live).
     *
     * <p>The default is {@code false}, correct for a mover with no search phase.
     * {@link dev.luizloyola.anima.core.config.Knob#PATHFINDER_IN_THREAD} closes the drift at its
     * source (the search runs inside the tick, so this is true for at most one tick), but it is
     * off by default and costs frame time, so asking here is what protects a task on any server.
     */
    default boolean routing() {
        return false;
    }

    /**
     * The route the legs are walking for the latest order, once it is worked out: null while it is
     * still being searched for, and with no order at all. What a task reads to judge the way before
     * it commits to it, as running for a shelter does. The default, for a mover with no routes to
     * show, is null.
     */
    default @Nullable Path route() {
        return null;
    }

    /**
     * The cell the latest order walks to, whoever gave it; null with no order, or from a mover that
     * cannot say. What lets a walk tell its own arrival from somebody else's.
     */
    default @Nullable Pos goal() {
        return null;
    }

    /**
     * No walk could be ordered from where the body stands: nowhere near it to stand. Counted as a
     * stranded walk from here, evidence of being shut in that the confinement sense's wider look
     * still has to prove, and that a look finding room clears. Ignored by default.
     */
    default void nowhereToGo() {
    }

    /**
     * Abandon the move in progress; {@link #state()} returns to {@link MoveState#IDLE}. Calling
     * this with no move in progress is a harmless no-op — tasks cancel unconditionally, and their
     * cancel must be idempotent, so the slack is absorbed here rather than guarded at every call
     * site.
     */
    void stop();
}
