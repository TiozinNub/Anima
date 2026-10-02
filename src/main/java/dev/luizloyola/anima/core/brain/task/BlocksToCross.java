package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.WalkLevel;
import java.util.List;
import java.util.function.Predicate;

/**
 * A walk stranded for want of blocks, in its place: get as many as the route a fuller pocket
 * would walk lays, then walk again, building (Luiz, 2026-10-02: "a person that fails stranded
 * should create a 'need' for blocks").
 *
 * <p>The blocks are {@link #SPEC}, which matches what a walk may lay but is its own spec, so a
 * consumer can teach it a way — digging the ground round the body — that would be wrong for a
 * standing stack of bridging blocks. Tried once: the walk it ends in gets no stand-in of its own.
 */
public final class BlocksToCross implements CompoundTask {

    /** Blocks a walk may lay, wanted to get across; the mod layer installs the rule. */
    public static final ItemSpec SPEC =
            ItemSpec.register(new ItemSpec("blocks_to_cross", id -> BlocksToCross.layable.test(id)));

    private static volatile Predicate<String> layable = id -> false;

    private final int x;
    private final int y;
    private final int z;
    private final Gait gait;
    private final int count;
    private final boolean leavesShelter;
    private final List<Method> methods = List.of(new FetchThenCross());

    public BlocksToCross(int x, int y, int z, Gait gait, int count, boolean leavesShelter) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.gait = gait;
        this.count = count;
        this.leavesShelter = leavesShelter;
    }

    /** Sets what {@link #SPEC} matches: Anima's {@code #anima:bridging_blocks}, as items. */
    public static void layableBy(Predicate<String> rule) {
        layable = rule;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public boolean standsIn() {
        return true;
    }

    @Override
    public String describe() {
        return "get " + count + " blocks to cross to (" + x + ", " + y + ", " + z + ")";
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Gait gait() {
        return gait;
    }

    public int count() {
        return count;
    }

    public boolean leavesShelter() {
        return leavesShelter;
    }

    private final class FetchThenCross implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            GoTo walk = new GoTo(x, y, z, gait, WalkLevel.BUILD);
            return List.of(new ObtainItem(SPEC, count), leavesShelter ? walk.leavingShelter() : walk);
        }

        @Override
        public String describe() {
            return "fetch them, then walk building";
        }
    }
}
