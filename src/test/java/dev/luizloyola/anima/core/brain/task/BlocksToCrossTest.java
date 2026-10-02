package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.nav.WalkLevel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A walk stranded for want of blocks gets them and walks again, building — once
 * (Luiz, 2026-10-02).
 */
class BlocksToCrossTest {

    private static final String DIRT = "minecraft:dirt";

    @BeforeAll
    static void dirtIsLaid() {
        BlocksToCross.layableBy(DIRT::equals);
    }

    /** Runs {@code plan} with the legs failing its first walk stranded, short of {@code needed}. */
    private static TaskExecutor strandedOnce(Task plan, FakeContext ctx, int needed) {
        TaskExecutor executor = new TaskExecutor();
        executor.run(plan, ctx);
        executor.tick(ctx); // issues the walk
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.STRANDED);
        ctx.mover.blocksNeeded = needed;
        executor.tick(ctx); // reads the stranding
        return executor;
    }

    private static List<String> journal(FakeContext ctx) {
        return ctx.journal().recent(50).stream()
                .map(entry -> entry.event() + " | " + entry.detail()).toList();
    }

    @Test
    void aWalkShortOfBlocksGetsThemThenWalksBuilding() {
        FakeContext ctx = new FakeContext();
        TaskExecutor executor = strandedOnce(new GoTo(9, 64, 0), ctx, 6);
        assertTrue(executor.isBusy(), "the walk stood down for the blocks, it did not fail");
        assertTrue(journal(ctx).contains("goto (9, 64, 0) | stranded — needs 6 blocks to get there"),
                () -> "journal: " + journal(ctx));

        assertTrue(executor.describe().contains("get 6 blocks to cross to (9, 64, 0)"),
                executor.describe());

        ctx.mover.setState(MoveState.IDLE);
        ctx.mover.setFailure(MoveFailure.NONE);
        ctx.mover.blocksNeeded = 0;
        int walks = ctx.mover.moveToCalls;
        ctx.percepts.inventory.add(ItemStack.of(DIRT, 6, 64));
        executor.tick(ctx);
        assertEquals(walks + 1, ctx.mover.moveToCalls, "with six in hand it walks again");
        assertEquals(WalkLevel.BUILD, ctx.mover.lastLevel);
        assertEquals(9, ctx.mover.lastX);

        ctx.mover.setState(MoveState.ARRIVED);
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals(Optional.of(TaskStatus.SUCCESS), executor.lastStatus());
    }

    @Test
    void withNoWayToGetThemTheWalkFailsWithoutWalkingAgain() {
        FakeContext ctx = new FakeContext();
        TaskExecutor executor = strandedOnce(new GoTo(9, 64, 0), ctx, 6);
        ctx.mover.setState(MoveState.IDLE);
        int walks = ctx.mover.moveToCalls;
        executor.tick(ctx);
        assertFalse(executor.isBusy());
        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus());
        assertEquals(walks, ctx.mover.moveToCalls);
    }

    @Test
    void theWalkAfterTheBlocksIsTriedOnce() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(ItemStack.of(DIRT, 6, 64));
        TaskExecutor executor = strandedOnce(new GoTo(9, 64, 0), ctx, 6);
        ctx.mover.setState(MoveState.IDLE);
        executor.tick(ctx); // the blocks are in hand: the walk again
        assertEquals(WalkLevel.BUILD, ctx.mover.lastLevel);
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.STRANDED);
        ctx.mover.blocksNeeded = 8;
        executor.tick(ctx);
        assertFalse(executor.isBusy(), "stranded again, it gives up rather than fetching again");
        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus());
    }

    @Test
    void aStrandingNoBlocksWouldCrossFailsAsBefore() {
        FakeContext ctx = new FakeContext();
        TaskExecutor executor = strandedOnce(new GoTo(9, 64, 0), ctx, 0);
        assertFalse(executor.isBusy());
        assertEquals(Optional.of(TaskStatus.FAILED), executor.lastStatus());
    }

    @Test
    void anUnreachableWalkFailsAsBefore() {
        FakeContext ctx = new FakeContext();
        TaskExecutor executor = new TaskExecutor();
        executor.run(new GoTo(9, 64, 0), ctx);
        executor.tick(ctx);
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.UNREACHABLE);
        ctx.mover.blocksNeeded = 6;
        executor.tick(ctx);
        assertFalse(executor.isBusy(), "only a stranding is a want of blocks");
    }

    /** Mid-errand, the stand-in takes the walk's place and the errand goes on after it. */
    @Test
    void anErrandCarriesOnAfterTheBlocks() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.inventory.add(ItemStack.of(DIRT, 6, 64));
        Task errand = new CompoundTask() {
            @Override
            public List<Method> methods() {
                return List.of(new Method() {
                    @Override
                    public boolean applicable(BrainContext c) {
                        return true;
                    }

                    @Override
                    public double estimateCost(BrainContext c) {
                        return 0;
                    }

                    @Override
                    public List<Task> decompose(BrainContext c) {
                        return List.of(new GoTo(9, 64, 0), new GoTo(12, 64, 0));
                    }

                    @Override
                    public String describe() {
                        return "two walks";
                    }
                });
            }

            @Override
            public String describe() {
                return "an errand";
            }
        };
        TaskExecutor executor = strandedOnce(errand, ctx, 6);
        ctx.mover.setState(MoveState.IDLE);
        executor.tick(ctx);
        assertEquals(9, ctx.mover.lastX, "the walk again, after the blocks");
        ctx.mover.setState(MoveState.ARRIVED);
        executor.tick(ctx);
        ctx.mover.setState(MoveState.IDLE);
        executor.tick(ctx);
        assertEquals(12, ctx.mover.lastX, "then the errand's next walk");
    }
}
