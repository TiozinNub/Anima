package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;

/**
 * Somebody called — go and stand in front of them.
 *
 * <p><b>It ends with nothing said, and that is the whole of rung 4.</b> The conversation this walk
 * is for arrives at rung 5; until then two bodies meet, face each other and part, which is an
 * honest picture of a mind that can hear a call and not yet talk.
 *
 * <p>Nothing here clears the hail mark: the SENSOR spends it on arrival (within
 * {@code Comfort.PERSONAL_SPACE} and identified), so a task never writes into perception, and a
 * hail answered by accident — the body was walking that way anyway — is spent too.
 */
public final class Answer implements CompoundTask {

    private final BeingId who;
    private final Pos where;
    private final List<Method> methods;

    public Answer(BeingId who, Pos where) {
        this.who = who;
        this.where = where;
        this.methods = List.of(new WalkOverAndFace());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "answer a call";
    }

    /** How long the pair stand facing before the beat ends — a second and a half. */
    public static final int FACE_TICKS = 30;

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Who called — the body this walk is toward. */
    public BeingId who() {
        return who;
    }

    /** Where they called from: their last known cell, which is all a hail carries. */
    public Pos where() {
        return where;
    }

    private final class WalkOverAndFace implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            double dx = where.x() - here.x();
            double dy = where.y() - here.y();
            double dz = where.z() - here.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            // The beat is a Face, not an Idle: the walk's own glance lapses ten ticks in, and a
            // hail is only spent once the hearer has the caller at INDIVIDUAL — which is a
            // question about where this head is pointed. See Face.
            return List.of(new GoTo(where.x(), where.y(), where.z()),
                    new Face(who, where, FACE_TICKS));
        }

        @Override
        public String describe() {
            return "walk over to " + who;
        }
    }
}
