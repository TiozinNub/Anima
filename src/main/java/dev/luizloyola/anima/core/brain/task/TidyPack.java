package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.inv.HandChanges;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.Tidy;
import java.util.Optional;

/**
 * Settle the pack into its {@link dev.luizloyola.anima.core.inv.PackLayout}, one timed swap at a
 * time ({@code handling.stack_ticks} each). Each swap is whole, so a tidy cut off midway leaves a
 * pack that is only less tidy.
 */
public final class TidyPack implements PrimitiveTask {

    /**
     * Swaps one tidy may make. A layout settles in far fewer; the cap is for one whose weights
     * would have it shuffle for ever.
     */
    public static final int MAX_MOVES = 18;

    private int moves;
    private Tidy.@org.jspecify.annotations.Nullable Swap swap;

    public TidyPack() {
        this(0);
    }

    /** As a save left it: how many swaps were already made. */
    public TidyPack(int moves) {
        this.moves = moves;
    }

    public int moves() {
        return moves;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Inventory pack = ctx.percepts().inventory();
        if (swap == null) {
            if (moves >= MAX_MOVES) {
                return TaskStatus.SUCCESS;
            }
            Optional<Tidy.Swap> next = Tidy.next(pack, pack.layout());
            if (next.isEmpty()) {
                return TaskStatus.SUCCESS;
            }
            swap = next.get();
        }
        HandChanges.Timing timing = new HandChanges.Timing(
                ctx.profile().i(ProfileAspect.HANDLING_SELECT_TICKS),
                ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS));
        if (HandChanges.move(pack, swap.from(), swap.to(), ctx.percepts().time(), timing)) {
            swap = null;
            moves++;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        swap = null;
    }

    @Override
    public String describe() {
        return "tidy the pack" + (moves > 0 ? " (" + moves + " moved)" : "");
    }
}
