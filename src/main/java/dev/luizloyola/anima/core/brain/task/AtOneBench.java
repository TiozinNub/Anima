package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A run of errands that share one workbench: each is tried in turn, a failure shrugged past so it
 * does not hold up the rest, and one {@link PackUpTable} ends the run. A table craft beneath it
 * leaves the table it put down standing ({@link #packsUpTables()}), so four tools made in a row put
 * one table down, not four (in-world, 2026-10-01: three put-downs in 3 s).
 *
 * <p>Always succeeds: whoever posted the run judges what it made.
 */
public final class AtOneBench implements CompoundTask {

    private final List<Task> work;
    private final List<Method> methods = List.of(new EachThenPackUp());

    public AtOneBench(List<Task> work) {
        this.work = List.copyOf(work);
    }

    /** The errands, in order — what the codec writes down. */
    public List<Task> work() {
        return work;
    }

    @Override
    public boolean packsUpTables() {
        return true;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "at one bench: " + work.stream().map(AtOneBench::describe)
                .collect(Collectors.joining(", "));
    }

    /** Each {@code Try} in the decomposition wraps the errand at the same place in {@link #work}. */
    @Override
    public void rejoin(List<Task> subtasks) {
        for (int i = 0; i < work.size() && i < subtasks.size(); i++) {
            if (subtasks.get(i) instanceof Try tried
                    && tried.attempt().getClass() == work.get(i).getClass()) {
                subtasks.set(i, new Try(work.get(i)));
            }
        }
    }

    private static String describe(Task task) {
        return task instanceof PrimitiveTask primitive
                ? primitive.describe() : ((CompoundTask) task).describe();
    }

    private final class EachThenPackUp implements Method {
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
            List<Task> plan = new ArrayList<>(work.size() + 1);
            for (Task errand : work) {
                plan.add(new Try(errand));
            }
            plan.add(new Try(new PackUpTable()));
            return plan;
        }

        @Override
        public String describe() {
            return "each in turn, then pack up";
        }
    }
}
