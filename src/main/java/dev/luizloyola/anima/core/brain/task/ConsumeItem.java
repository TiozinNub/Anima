package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.ConsumeState;

/**
 * The second primitive: eat what is in an inventory slot — the thinnest wrapper over the
 * {@link dev.luizloyola.anima.core.brain.act.ItemConsumer} port, as {@link GoTo} wraps the mover.
 * The port owns the whole transaction (slot shuffle, item-use, nutrition, stack decrement); this
 * task starts it and maps its lifecycle onto {@link TaskStatus}.
 *
 * <p><b>First tick:</b> issue {@code begin(slot)}, do not read {@code state()} — the {@link GoTo}
 * rule, whose class doc has the reasoning. The refinement: {@code begin} answers synchronously
 * whether there was anything to eat, so {@code false} is an immediate first-tick FAILED.
 *
 * <p>Later ticks map CONSUMING → RUNNING, FINISHED → SUCCESS (the body already applied the
 * nutrition), FAILED → FAILED, and an unexpected IDLE → FAILED: no claiming a meal it did not
 * finish.
 */
public final class ConsumeItem implements PrimitiveTask {
    private final int slot;
    private boolean issued;

    /** @param slot the core inventory slot (41-slot indexing) whose stack to consume */
    public ConsumeItem(int slot) {
        this.slot = slot;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (!issued) {
            issued = true;
            // See class doc: issue, don't read — but begin's own answer is this tick's truth.
            return ctx.actuators().consumer().begin(slot) ? TaskStatus.RUNNING : TaskStatus.FAILED;
        }
        ConsumeState state = ctx.actuators().consumer().state();
        switch (state) {
            case CONSUMING:
                return TaskStatus.RUNNING;
            case FINISHED:
                return TaskStatus.SUCCESS;
            case FAILED:
            case IDLE:
            default:
                // FAILED: the bite died. IDLE: someone else stopped the gullet — the task
                // cannot claim a meal it did not finish.
                return TaskStatus.FAILED;
        }
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Idempotent because ItemConsumer.abort() is safe when idle (see its doc) — before the
        // first tick, after FINISHED, or twice in a row.
        ctx.actuators().consumer().abort();
    }

    @Override
    public String describe() {
        return "consume slot " + slot;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public int slot() {
        return slot;
    }

    /** Whether the eat has already been ordered — a reload must not order it a second time. */
    public boolean issued() {
        return issued;
    }

    public ConsumeItem resume(boolean issued) {
        this.issued = issued;
        return this;
    }
}
