package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.speech.Speech;
import java.util.List;

/**
 * Somebody called — go and answer them.
 *
 * <p>The walk flows straight into the conversation the hail was for: {@link Converse} faces every
 * tick on its own, so the stand-and-look beat this used to end on is subsumed rather than run
 * first. The answerer only exists because it was hailed, so the conversation opens credited
 * {@link Speech.Opening#THEY_HAILED} — the prefilled line is the caller's, authored back when they
 * hailed, not asked for again here.
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
        this.methods = List.of(new WalkOverAndTalk());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "answer a call";
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Who called — the body this walk is toward. */
    public BeingId who() {
        return who;
    }

    /** Where they called from: their last known cell, which is all a hail carries. */
    public Pos where() {
        return where;
    }

    private final class WalkOverAndTalk implements Method {
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
            return List.of(new GoTo(where.x(), where.y(), where.z()),
                    new Converse(who, Speech.Opening.THEY_HAILED));
        }

        @Override
        public String describe() {
            return "walk over to " + who;
        }
    }
}
