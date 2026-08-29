package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import org.jspecify.annotations.Nullable;

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
 * sight mid-conversation. The live percept also carries how high that body's face is, so only the
 * remembered case falls back to {@link #FACE_HEIGHT}.
 */
public final class Face implements PrimitiveTask {

    /**
     * The aim when the target is NOT perceived, and only then — a remembered cell carries no body
     * to measure. A perceived one is aimed at through its own {@link Being#eyeHeight()}, which is
     * what puts the look on a wolf's eyes rather than over its head.
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
        Being seen = seen(ctx);
        Pos at = seen == null ? where : seen.pos();
        double face = seen == null ? FACE_HEIGHT : seen.eyeHeight();
        // WORK, the rank a deliberate act looks at what it is doing with — standing in front of
        // somebody IS the act here, so the walk's own NAV glance must not outrank it. Held one
        // tick and re-asked, since the aim moves with them.
        ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + face, at.z() + 0.5,
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

    /** Them as perceived right now, or null — the caller falls back to {@link #where}. */
    private @Nullable Being seen(BrainContext ctx) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(who)) {
                return being;
            }
        }
        return null;
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
