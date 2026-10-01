package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.store.Store;
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

    /**
     * The count, or what the pack and the stores seen hold when that is less: a job for twelve
     * failed for ever once four of them had gone (2026-10-01). The count itself when nothing is
     * known to hand, so the fetch fails as it should.
     */
    int toHand(BrainContext ctx) {
        int have = ctx.percepts().inventory().count(raw.matcher());
        long now = ctx.percepts().time();
        for (PoiMemory store : Store.ours(ctx)) {
            if (!ctx.knowledge().isAvoided(Store.POI, store.anchor(), now)) {
                have += ctx.knowledge().insideOf(store.anchor()).map(seen -> seen.count(raw)).orElse(0);
            }
        }
        return have > 0 ? Math.min(count, have) : count;
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
            int n = toHand(ctx);
            List<Task> steps = new ArrayList<>(LoadFurnace.walkTo(ctx, at));
            steps.add(new ObtainItem(raw, n));
            steps.add(LoadFurnace.backBeside(ctx, at));
            steps.add(new TendCampfires(at, raw, n));
            return steps;
        }

        @Override
        public String describe() {
            return "get it, then cook it";
        }
    }
}
