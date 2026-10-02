package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shrug wrapper, run through the real executor: a failing attempt burns its method and the
 * empty fallback succeeds.
 */
class TryTest {

    private final FakeContext ctx = new FakeContext();
    private final TaskExecutor executor = new TaskExecutor();

    private final class Scripted implements PrimitiveTask {
        final TaskStatus ending;
        int runs;

        Scripted(TaskStatus ending) {
            this.ending = ending;
        }

        @Override
        public TaskStatus tick(BrainContext c) {
            runs++;
            return ending;
        }

        @Override
        public void cancel(BrainContext c) {
        }

        @Override
        public String describe() {
            return "scripted " + ending;
        }
    }

    private Optional<TaskStatus> drive(Task root, int maxTicks) {
        executor.run(root, ctx);
        for (int i = 0; i < maxTicks && executor.isBusy(); i++) {
            executor.tick(ctx);
        }
        return executor.lastStatus();
    }

    @Test
    void aSucceedingAttemptPassesStraightThrough() {
        Scripted child = new Scripted(TaskStatus.SUCCESS);
        assertEquals(Optional.of(TaskStatus.SUCCESS), drive(new Try(child), 10));
        assertEquals(1, child.runs);
    }

    @Test
    void aFailingAttemptIsShruggedIntoSuccess() {
        Scripted child = new Scripted(TaskStatus.FAILED);
        assertEquals(Optional.of(TaskStatus.SUCCESS), drive(new Try(child), 10),
                "a want that cannot be had must never fail the errand carrying it");
        assertEquals(1, child.runs, "the attempt ran once — the shrug is not a retry loop");
    }

    /** One method, costing {@code cost}, that runs {@code steps} in order. */
    private static CompoundTask doing(String name, double cost, Task... steps) {
        return new CompoundTask() {
            @Override
            public List<Method> methods() {
                return List.of(new Method() {
                    @Override
                    public boolean applicable(BrainContext c) {
                        return true;
                    }

                    @Override
                    public double estimateCost(BrainContext c) {
                        return cost;
                    }

                    @Override
                    public List<Task> decompose(BrainContext c) {
                        return List.of(steps);
                    }

                    @Override
                    public String describe() {
                        return name;
                    }
                });
            }

            @Override
            public String describe() {
                return name;
            }
        };
    }

    @Test
    void aShruggedOffPriceIsNotBlamedForWhatFailsNext() {
        ctx.costTolerance = 10;
        Task farAxe = doing("fetch an axe", 50, new Scripted(TaskStatus.SUCCESS));
        Task chop = doing("chop", 0, new Scripted(TaskStatus.FAILED));
        assertEquals(Optional.of(TaskStatus.FAILED), drive(doing("errand", 0, new Try(farAxe), chop), 10));
        assertFalse(executor.failedOnPrice(),
                "the axe's price was shrugged off; the chop failed on its own");
        assertTrue(executor.failureReason().orElseThrow().contains("scripted FAILED"),
                "the reason names the step that failed");
    }
}
