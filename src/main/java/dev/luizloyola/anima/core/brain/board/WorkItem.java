package dev.luizloyola.anima.core.brain.board;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.inv.Kit;

/**
 * One claimable unit of work. An {@code Instinct} bids continuous {@code pressure(ctx)} and is
 * never owed anything; a work item bids a fixed {@link #priority()} and once claimed is OWED —
 * suspension keeps the claim, resume builds a fresh {@link #root()} against the changed world,
 * prior progress counting automatically. The arbiter never learns what the work is.
 */
public interface WorkItem {
    /** The demand bid, on the instincts' 0..1 pressure scale — board policy, not body state. */
    double priority();

    /**
     * What taking this on is expected to cost <em>the asking agent</em>, on the same 0..1 scale as
     * {@link #priority()} — the discriminator a board subtracts when several items are open. Zero
     * by default.
     *
     * <p>This makes claim scoring per-asker: an item that knows a place overrides it with distance,
     * so the nearest member wins without an auction. Not the task layer's {@code estimateCost},
     * which prices methods against the arbiter's tolerance in blocks-and-staleness.
     */
    default double estimatedCost(BrainContext ctx) {
        return 0.0;
    }

    /** A FRESH task tree that pursues this item — called anew on every grant and resume. */
    Task root();

    /**
     * What working this item calls for — see {@link Kit}. The board's offer path skips an asker
     * whose pack is missing a NEED; WANTs gate nothing and are wielded opportunistically at the
     * block. Defaults to nothing.
     */
    default Kit kit() {
        return Kit.NONE;
    }

    /** One-line name for journal and board readouts, e.g. {@code "acquire logs x16"}. */
    String describe();

    /** Short progress note for the resume/status lines, e.g. {@code "9/16 held"}; may be empty. */
    default String progress(BrainContext ctx) {
        return "";
    }
}
