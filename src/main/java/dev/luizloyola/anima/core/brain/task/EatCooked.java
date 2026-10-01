package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Campfire;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.store.Store;
import java.util.List;
import java.util.Optional;

/**
 * Tier 6 of {@link SatisfyHunger}: cook raw food at a campfire and eat it (directions spec, decision
 * 24) — raw food carried, or seen in one of the party's stores. Priced by the walk, through the
 * store when the food is there, and {@link #WAIT} for the round at the fire, so a hungry body cooks
 * the beef it has rather than eating it raw, and forages instead when berries are nearer.
 *
 * <p>Never at an empty bar: a body losing health eats raw at once rather than stand 30 seconds by
 * a fire ({@link EatAnything}). Never a hunt: {@link RawFood} names no item a producer makes.
 */
public final class EatCooked implements Method {

    /**
     * Walk blocks a round at the fire is priced at: well under a hungry body's 60, so a fire 40
     * blocks off is still worth it, and over a peckish body's 15, which waits until it is hungry.
     */
    public static final double WAIT = 20.0;

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.hasRoom(ctx) && ctx.percepts().metabolism().foodLevel() > 0
                && fire(ctx).isPresent() && (carried(ctx) > 0 || store(ctx).isPresent());
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        Optional<Pos> fire = fire(ctx);
        if (fire.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        Pos here = ctx.percepts().position();
        if (carried(ctx) > 0) {
            return Workbench.distance(here, fire.get()) + WAIT;
        }
        return store(ctx).map(at -> Workbench.distance(here, at) + Workbench.distance(at, fire.get()) + WAIT)
                .orElse(Double.POSITIVE_INFINITY);
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        Pos fire = fire(ctx).orElseThrow();
        return List.of(new CookAtCampfire(fire, RawFood.SPEC, mealSize(ctx)), new EatCarried());
    }

    @Override
    public String describe() {
        return "cook food and eat it";
    }

    /**
     * How many to cook: enough to fill the bar at the richest cooked form of what is to hand, as far
     * as the pack and the stores seen hold, so the fetch never asks for more than there is.
     */
    static int mealSize(BrainContext ctx) {
        int missing = Metabolism.MAX_FOOD - ctx.percepts().metabolism().foodLevel();
        FoodLookup foods = ctx.percepts().foods();
        int richest = 0;
        int have = 0;
        for (ItemStack stack : toHand(ctx)) {
            if (cooks(ctx, stack)) {
                richest = Math.max(richest, foods.cookedForm(stack).map(FoodValue::nutrition).orElse(0));
                have += stack.count();
            }
        }
        int each = Math.max(1, richest);
        return Math.max(1, Math.min(have, (missing + each - 1) / each));
    }

    /** The known campfire nearest this body. */
    private static Optional<Pos> fire(BrainContext ctx) {
        return ctx.knowledge().nearest(Campfire.POI, ctx.percepts().position()).map(PoiMemory::anchor);
    }

    private static int carried(BrainContext ctx) {
        int n = 0;
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            if (cooks(ctx, entry.stack())) {
                n += entry.stack().count();
            }
        }
        return n;
    }

    /** The party's store nearest this body that was seen holding raw food a campfire cooks. */
    private static Optional<Pos> store(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        Pos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PoiMemory store : Store.ours(ctx)) {
            if (ctx.knowledge().isAvoided(Store.POI, store.anchor(), now)) {
                continue;
            }
            boolean holds = ctx.knowledge().insideOf(store.anchor())
                    .map(seen -> seen.stacks().stream().anyMatch(stack -> cooks(ctx, stack))).orElse(false);
            double distance = Workbench.distance(here, store.anchor());
            if (holds && distance < bestDistance) {
                best = store.anchor();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** What the pack holds and the party's stores were seen holding. */
    private static List<ItemStack> toHand(BrainContext ctx) {
        List<ItemStack> all = new java.util.ArrayList<>();
        for (Inventory.Entry entry : ctx.percepts().inventory().occupied()) {
            all.add(entry.stack());
        }
        long now = ctx.percepts().time();
        for (PoiMemory store : Store.ours(ctx)) {
            if (!ctx.knowledge().isAvoided(Store.POI, store.anchor(), now)) {
                ctx.knowledge().insideOf(store.anchor()).ifPresent(seen -> all.addAll(seen.stacks()));
            }
        }
        return all;
    }

    /** Raw food a campfire makes better. */
    private static boolean cooks(BrainContext ctx, ItemStack stack) {
        return RawFood.SPEC.matches(stack.id()) && ctx.percepts().smelting().campfire(stack.id()).isPresent();
    }
}
