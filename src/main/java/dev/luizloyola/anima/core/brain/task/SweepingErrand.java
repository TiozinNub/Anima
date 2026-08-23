package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.Coverage;
import java.util.List;

/**
 * Any errand, plus a map to fill in while it runs. A body walking to a tree collects exactly the
 * evidence a surveyor collects on the same ground — the near field is always on and does not care
 * which task holds the wheel. Until 2026-08-23 that was thrown away, and a second pass walked the
 * same ground to learn what the chopper already knew.
 *
 * <p><b>The crediting is the {@link TaskExecutor}'s</b>, off {@link Task#coverage()}: this only
 * declares the sink. That is what lets a wrapper collect ground through every leg of a
 * decomposition it never sees — the walk out, the work, and the walk home.
 */
public final class SweepingErrand implements CompoundTask {

    private final Task work;
    private final Coverage coverage;
    private final List<Method> methods;

    public SweepingErrand(Task work, Coverage coverage) {
        this.work = work;
        this.coverage = coverage;
        this.methods = List.of(new JustTheWork());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public Coverage coverage() {
        return coverage;
    }

    /**
     * The wrapped work's own answer, not a fresh one. Before this expands, the running chain is
     * only this node — a chop that answered {@code false} here would let the escape drive preempt
     * the fell and mine out the pillar the body stands on.
     */
    @Override
    public boolean reshapesGround() {
        return work.reshapesGround();
    }

    @Override
    public String describe() {
        return describeWork();
    }

    public Task work() {
        return work;
    }

    private String describeWork() {
        if (work instanceof PrimitiveTask primitive) {
            return primitive.describe();
        }
        return ((CompoundTask) work).describe();
    }

    /** The one way: the wrapped work, unchanged. */
    private final class JustTheWork implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(work);
        }

        @Override
        public String describe() {
            return "get on with it";
        }
    }
}
