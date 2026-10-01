package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Cook {@code count} of {@code raw} at the campfire at {@code at} and the ones beside it: walk
 * there, get what is to be cooked, and stay until it is done ({@link TendCampfires}). The walk comes
 * first for the furnace's reason: a campfire stands at a base, beside its stores.
 */
public final class CookAtCampfire implements CompoundTask {

    private final Pos at;
    private final ItemSpec raw;
    private final int count;
    private final List<Method> methods = List.of(new Cook());

    public CookAtCampfire(Pos at, ItemSpec raw, int count) {
        this.at = at;
        this.raw = raw;
        this.count = Math.max(1, Math.min(64, count));
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "cook " + count + " " + raw.name() + " on the campfire";
    }

    public Pos at() {
        return at;
    }

    public ItemSpec raw() {
        return raw;
    }

    public int count() {
        return count;
    }

    private final class Cook implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        /** A job that comes to a campfire already chose the walk, as one that comes to a furnace. */
        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Task> steps = new ArrayList<>(LoadFurnace.walkTo(ctx, at));
            steps.add(new ObtainItem(raw, count));
            steps.add(LoadFurnace.backBeside(ctx, at));
            steps.add(new TendCampfires(at, raw, count));
            return steps;
        }

        @Override
        public String describe() {
            return "get it, then cook it";
        }
    }
}
