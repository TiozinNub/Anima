package dev.luizloyola.anima.core.brain.task;

/**
 * A node of the task tree — sealed over exactly two kinds: {@link PrimitiveTask} does (an
 * executable leaf state machine), {@link CompoundTask} decides (a goal achieved by one of several
 * {@link Method}s). New behavior arrives as new primitives and methods, never a third node kind.
 *
 * <p>{@link Method#decompose} returns freshly built {@code Task} instances: a compound expands
 * lazily, against <em>current</em> percepts, never resuming stale ones.
 */
public sealed interface Task permits PrimitiveTask, CompoundTask {

    /**
     * Whether this is work that places or breaks <em>structural</em> blocks — the ones that hold a
     * body up or shut it in. Mining, chopping and building say yes; planting, ranching, fighting
     * and fetching say no.
     *
     * <p><b>A body doing this work must not diagnose itself as stuck.</b> It is somewhere
     * precarious ON PURPOSE and carries its own way back down: a chop's mast top is a one-cell
     * region with no legal move out, which is not the same fact as being trapped. Ungated, the
     * escape drive preempted the fell at 0.90 and mined out the pillar the body stood on.
     *
     * <p>Answered by the OPERATION, not the primitive it bottoms out in — {@link BreakBlock} is
     * shared with the escape itself, {@code ChopPlannedTree} is not. The executor asks the whole
     * running chain, so a nested operation counts.
     *
     * <p><b>{@link EscapeStep} answers no, and must:</b> it is the RESPONSE to being stuck and
     * re-reads the confinement verdict every step, or it would cut one block, wander off, and come
     * back.
     */
    default boolean reshapesGround() {
        return false;
    }
}
