package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;

/**
 * Attempt a task; succeed either way. A compound with exactly two methods: the attempt (decomposing
 * to the child) and giving up (decomposing to nothing, which trivially succeeds). A failing child
 * burns the attempt's tried-mark, the executor falls back to the empty method, and the parent moves
 * on.
 *
 * <p>For a {@link KittedErrand}'s WANTS: "chopping goes better with an axe" must send a body after
 * an axe when one can be had and never block the chop when one cannot.
 *
 * <p>The child is held as the instance the attempt will run — safe because a {@code Try} is always
 * freshly built by its parent's decompose.
 */
public final class Try implements CompoundTask {

    private final Task attempt;
    private final List<Method> methods;

    public Try(Task attempt) {
        this.attempt = attempt;
        this.methods = List.of(new Attempt(), new GiveUp());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "try: " + describeChild();
    }

    /** The wrapped task — what the codec writes down. */
    public Task attempt() {
        return attempt;
    }

    private String describeChild() {
        if (attempt instanceof PrimitiveTask primitive) {
            return primitive.describe();
        }
        return ((CompoundTask) attempt).describe();
    }

    /** The real thing: costs nothing extra, decomposes to the child, fails if it fails. */
    private final class Attempt implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0; // ties go to the earlier entry, so the attempt always runs before the shrug
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(attempt);
        }

        @Override
        public String describe() {
            return describeChild();
        }
    }

    /** The shrug: an empty decomposition trivially succeeds, and the parent moves on. */
    private static final class GiveUp implements Method {
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
            return List.of();
        }

        @Override
        public String describe() {
            return "let it go";
        }
    }
}
