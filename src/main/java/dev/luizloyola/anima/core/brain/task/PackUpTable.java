package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Take back a table this body put down to craft on ({@link NoteFieldTable}), once the craft is
 * made: a body on its own left sixteen behind over one run (in-world, 2026-09-10). Satisfied when
 * none of its own stands within reach. A base's table, or anybody else's, is never its own, so it
 * stays where it is.
 *
 * <p>Only within reach: one left further off by a craft cut short is taken when a later craft
 * walks back to it, not by a walk of its own.
 */
public final class PackUpTable implements AchieveTask {

    private final List<Method> methods = List.of(new BreakAndGather());

    @Override
    public boolean satisfied(BrainContext ctx) {
        return inReach(ctx) == null;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "pick my workbench back up";
    }

    /**
     * The nearest of this body's own tables within reach. One the world no longer backs (somebody
     * broke it, or this body just did) is struck off on the way.
     */
    private static @Nullable Pos inReach(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Pos best = null;
        double bestDistance = Workbench.REACH;
        for (Pos at : ctx.fieldTables().snapshot()) {
            double distance = Workbench.distance(at, here);
            if (distance > Workbench.REACH) {
                continue;
            }
            if (ctx.percepts().blocks().at(at.x(), at.y(), at.z()) != Workbench.BLOCK) {
                ctx.fieldTables().forget(at);
                ctx.knowledge().disprove(Workbench.POI, at);
                continue;
            }
            if (distance <= bestDistance) {
                best = at;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static final class BreakAndGather implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return inReach(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos at = inReach(ctx);
            // Beside the body the walk-over pickup usually has it before the sweep looks.
            return List.of(new BreakBlock(at.x(), at.y(), at.z()),
                    new Try(new GatherNearbyDrops(ItemSpec.anyOf(Set.of(Workbench.ITEM_ID)), true)));
        }

        @Override
        public String describe() {
            return "break it and pick it up";
        }
    }
}
