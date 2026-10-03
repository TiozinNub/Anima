package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import java.util.List;

/**
 * Tier 7 of {@link SatisfyHunger}: go to somebody and ask for food, then eat what they give
 * (2026-10-02-food-and-replies-design.md). The walk and the hail are {@link SeekCompany}'s — the
 * hail is the same hail, and only the first line after the greeting says what the caller wants,
 * which is the chooser's to say once it reads its own hunger. A refusal leaves the pack empty, so
 * {@link EatCarried} fails the method and the arbiter's cooldown paces the next try; the target is
 * marked called, so the next try asks somebody else.
 *
 * <p>Priced as a walk to them plus {@link #ASK_PREMIUM}: food in hand or in a store nearby wins,
 * and a stranger is asked before a field is foraged far off.
 */
public final class AskForFood implements Method {

    /** What asking costs beyond the walk, in blocks of walking: a store this much nearer wins. */
    static final double ASK_PREMIUM = 24.0;

    @Override
    public boolean applicable(BrainContext ctx) {
        return EatSelection.hasRoom(ctx)
                && EatSelection.bestReady(ctx).isEmpty()
                && SeekCompany.possible(ctx, null);
    }

    @Override
    public double estimateCost(BrainContext ctx) {
        double distance = SeekCompany.distanceToNearest(ctx);
        return distance == Double.MAX_VALUE ? distance : distance + ASK_PREMIUM;
    }

    @Override
    public List<Task> decompose(BrainContext ctx) {
        return List.of(new SeekCompany(), new EatCarried());
    }

    @Override
    public String describe() {
        return "ask somebody for food";
    }
}
