package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Stand and look at somebody for a beat — the pause {@link Answer} ends on.
 *
 * <p><b>The look and the beat are one task on purpose.</b> An {@link Idle} beside a one-shot gaze
 * claim was the first shape, and it lapses: the claim is issued when the walk ends, the caller
 * moves, and the head stays pointed at a patch of grass. The hearer's own sensor only spends the
 * hail mark at {@code Identified.INDIVIDUAL}, which needs the caller inside the vision cone — and
 * a body's cone follows its head. A beat that stops looking is a pair shuffling in place.
 *
 * <p>So the claim is re-asked every tick, at the target's LIVE cell while it is still perceived
 * and at its last known one otherwise: the same thing a person does when someone steps out of
 * sight mid-conversation.
 */
public final class Face implements PrimitiveTask {

    /**
     * How far above the cell a body stands in its face is. One number rather than an aspect: the
     * looking body cannot know another species' eye height, and the gaze organ eases the head
     * anyway — this only has to beat staring at their boots.
     */
    public static final double FACE_HEIGHT = 1.5;

    private final BeingId who;
    private final Pos where;
    private final int ticks;
    private int remaining;

    /**
     * @param who whose face to hold; perceived or not, the beat runs either way
     * @param where where they were last known to be — the fallback aim
     * @param ticks how many ticks to report RUNNING before SUCCESS, as {@link Idle} counts
     */
    public Face(BeingId who, Pos where, int ticks) {
        this.who = who;
        this.where = where;
        this.ticks = ticks;
        this.remaining = ticks;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (remaining <= 0) {
            return TaskStatus.SUCCESS;
        }
        remaining--;
        Pos at = seen(ctx);
        // WORK, the rank a deliberate act looks at what it is doing with — standing in front of
        // somebody IS the act here, so the walk's own NAV glance must not outrank it. Held one
        // tick and re-asked, since the aim moves with them.
        ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + FACE_HEIGHT, at.z() + 0.5,
                Gazer.Priority.WORK);
        return TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing to release: a gaze claim is a claim, not a hold, and expires on its own.
    }

    @Override
    public String describe() {
        return "face " + who;
    }

    /** Where they are now if they are still perceived, else where they were. */
    private Pos seen(BrainContext ctx) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(who)) {
                return being.pos();
            }
        }
        return where;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Whose face this beat is held on. */
    public BeingId who() {
        return who;
    }

    /** Where they were when the beat was ordered — the aim when they are no longer perceived. */
    public Pos where() {
        return where;
    }

    /** The beat as ordered. */
    public int ticks() {
        return ticks;
    }

    /** How much of it is left — a beat that restarts is one both parties would stand through. */
    public int remaining() {
        return remaining;
    }

    public Face resume(int remaining) {
        this.remaining = remaining;
        return this;
    }
}
