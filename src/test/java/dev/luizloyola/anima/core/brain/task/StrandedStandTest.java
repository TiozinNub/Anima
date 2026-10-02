package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.history.Unreached;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Depot;
import dev.luizloyola.anima.core.store.Store;
import dev.luizloyola.anima.core.territory.ChunkKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What happens when no walk gets to a store's stand: forest, 2026-10-02, a stand on a roof was
 * searched for every two ticks for minutes, each errand re-ordering the walk 32 rounds of 32 times.
 */
class StrandedStandTest {

    /** The house chest, against the house's east wall. */
    private static final Pos CHEST = new Pos(10, 64, 10);
    private static final int ROOF_Y = 67;

    /** A shut house: inside x 8..10, z 9..11, three high, walled and roofed; floor the ground. */
    private static void house(FakeContext ctx) {
        for (int x = 7; x <= 11; x++) {
            for (int z = 8; z <= 12; z++) {
                boolean wall = x == 7 || x == 11 || z == 8 || z == 12;
                for (int y = 64; y < ROOF_Y; y++) {
                    if (wall) {
                        ctx.percepts.blocks.set(x, y, z, BlockKind.OTHER);
                    }
                }
                ctx.percepts.blocks.set(x, ROOF_Y, z, BlockKind.OTHER);
            }
        }
        ctx.percepts.blocks.set(CHEST.x(), CHEST.y(), CHEST.z(), Store.BLOCK);
        ctx.claim(Store.POI, CHEST);
    }

    private static void home(FakeContext ctx, Pos hint) {
        ctx.depot = Optional.of(new Depot.Site(hint,
                Set.of(ChunkKey.at(ChunkKey.OVERWORLD, hint.x(), hint.z()))));
    }

    private static boolean inside(Pos cell) {
        return cell.x() >= 8 && cell.x() <= 10 && cell.z() >= 9 && cell.z() <= 11 && cell.y() == 64;
    }

    private static Pos walkedTo(List<Task> steps) {
        return steps.stream().filter(step -> step instanceof GoTo).map(step -> (GoTo) step)
                .map(walk -> new Pos(walk.x(), walk.y(), walk.z())).findFirst()
                .orElseThrow(() -> new AssertionError("no walk: " + steps));
    }

    @Test
    void aStruckStandIsPassedOverForAnotherSide() {
        FakeContext ctx = new FakeContext();
        house(ctx);
        ctx.percepts.position = new Pos(9, 64, 11);
        home(ctx, CHEST);
        Method walkToKnown = new EnsureStore().methods().get(0);
        Pos first = walkedTo(walkToKnown.decompose(ctx));

        ctx.unreached.strike(first, ctx.percepts.time);
        Pos second = walkedTo(walkToKnown.decompose(ctx));

        assertFalse(first.equals(second), "a stand no walk got to is not walked to again");
        assertTrue(inside(second));
    }

    @Test
    void aStoreWithEverySideStruckIsNoWayAtAll() {
        FakeContext ctx = new FakeContext();
        house(ctx);
        ctx.percepts.position = new Pos(9, 64, 11);
        home(ctx, CHEST);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ctx.unreached.strike(new Pos(CHEST.x() + dx, CHEST.y(), CHEST.z() + dz),
                        ctx.percepts.time);
            }
        }
        EnsureStore goal = new EnsureStore();

        assertTrue(goal.methods().stream().noneMatch(way -> way.applicable(ctx)),
                "no side left to walk to, and building another beside it is not the answer");
        assertFalse(new TakeFromStore(ItemSpec.anyOf(Set.of("minecraft:oak_log")), 1)
                .applicable(ctx), "nor is a fetch from it");
    }

    /**
     * Every walk strands, as every walk to the roof did. Each stand is walked to once; with none
     * left the stow fails, and its retry walks nowhere until the strikes lapse.
     */
    @Test
    void aStowWhoseWalksAllStrandFailsInsteadOfLooping() {
        FakeContext ctx = new FakeContext();
        ctx.percepts.position = new Pos(0, 64, 0);
        home(ctx, new Pos(10, 64, 10));
        for (int slot = 0; slot < 3; slot++) {
            ctx.percepts.inventory().set(slot, ItemStack.of("minecraft:oak_log", 64, 64));
        }
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.STRANDED);

        List<Pos> walks = stow(ctx);

        assertEquals(Optional.of(TaskStatus.FAILED), lastStatus);
        assertEquals(walks.size(), new HashSet<>(walks).size(),
                "no stand walked to twice: " + walks);
        assertTrue(walks.size() < 100, "bounded by the stands there are, not by 32 rounds of 32: "
                + walks.size());

        assertTrue(stow(ctx).isEmpty(), "the retry finds every stand still struck and walks nowhere");
        assertEquals(Optional.of(TaskStatus.FAILED), lastStatus);

        ctx.percepts.time += Unreached.LIFETIME_TICKS;
        assertFalse(stow(ctx).isEmpty(), "a back-off, not a ban: once the strikes lapse it tries again");
    }

    @Test
    void aWalkThatGetsThereLiftsTheStrike() {
        FakeContext ctx = new FakeContext();
        Pos stand = new Pos(3, 64, 0);
        GoTo stranded = new GoTo(stand.x(), stand.y(), stand.z());
        ctx.mover.setState(MoveState.FAILED);
        ctx.mover.setFailure(MoveFailure.STRANDED);
        stranded.tick(ctx);
        assertEquals(TaskStatus.FAILED, stranded.tick(ctx));
        assertTrue(ctx.unreached.struck(stand, ctx.percepts.time));

        GoTo arrives = new GoTo(stand.x(), stand.y(), stand.z());
        ctx.mover.setState(MoveState.ARRIVED);
        arrives.tick(ctx);
        assertEquals(TaskStatus.SUCCESS, arrives.tick(ctx));
        assertFalse(ctx.unreached.struck(stand, ctx.percepts.time));
    }

    private Optional<TaskStatus> lastStatus;

    private List<Pos> stow(FakeContext ctx) {
        ctx.mover.setState(MoveState.FAILED);
        TaskExecutor executor = new TaskExecutor();
        executor.run(new PutAwaySurplus(0), ctx);
        List<Pos> walks = new ArrayList<>();
        int seen = ctx.mover.moveToCalls;
        for (int tick = 0; tick < 20_000 && executor.isBusy(); tick++) {
            ctx.percepts.time++;
            executor.tick(ctx);
            if (ctx.mover.moveToCalls > seen) {
                seen = ctx.mover.moveToCalls;
                walks.add(new Pos(ctx.mover.lastX, ctx.mover.lastY, ctx.mover.lastZ));
            }
        }
        assertFalse(executor.isBusy(), "still going after 20,000 ticks: " + executor.describe());
        lastStatus = executor.lastStatus();
        return walks;
    }
}
