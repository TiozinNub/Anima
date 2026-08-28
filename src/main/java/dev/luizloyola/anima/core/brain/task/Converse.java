package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechActs;

/**
 * Participating in a conversation is a task like any other — it holds the body in place,
 * turns the head, and takes turns on this brain's own ticks. Cancel leaves the record OPEN
 * on purpose: interruption is not rudeness, and the other side's patience or the staleness
 * gap ends what we walked away from.
 *
 * <p><b>Taking over means walking into range first.</b> A body handed this from beyond
 * {@code social.chat_radius} — the resume pull can grant it across an open field — closes the
 * distance before a word is said; see {@link #closeIn}. The walk is deliberately not part of the
 * saved state: a restored Converse just re-measures distance on its first tick, the same way it
 * re-resolves the encounter itself.
 */
public final class Converse implements PrimitiveTask {

    private final BeingId other;
    private final Speech.Opening opening;
    private Encounter encounter;
    /** The walk toward the counterpart while out of chat range — see {@link #closeIn}. Never saved. */
    private GoTo walk;

    public Converse(BeingId other, Speech.Opening opening) {
        this.other = other;
        this.opening = opening;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Speech speech = ctx.speech();
        if (encounter == null) {
            encounter = speech.current().orElseGet(() -> speech.join(other, opening));
        }
        if (encounter.closed()) {
            return TaskStatus.SUCCESS;
        }
        Being counterpart = findCounterpart(ctx);
        if (counterpart != null
                && counterpart.distance() > ctx.profile().i(ProfileAspect.SOCIAL_CHAT_RADIUS)) {
            return closeIn(ctx, counterpart);
        }
        if (walk != null) {
            // Within range now, or the counterpart dropped out of sight — either way the walk is
            // spent; a live order left behind would keep the legs moving toward a stale cell.
            walk.cancel(ctx);
            walk = null;
        }
        face(ctx, counterpart);
        if (speech.maySpeak(encounter)) {
            Chooser.Line line = speech.chooser().choose(ctx, speech.turn(encounter));
            if (line != null) {
                speech.say(encounter, line);
                ctx.journal().record(Category.BRAIN, "converse", "said " + line.act().key());
                if (encounter.closed()) {
                    return TaskStatus.SUCCESS;
                }
            }
            return TaskStatus.RUNNING;
        }
        return speech.expiredObligation(encounter).map(snubbed -> {
            speech.system(encounter, SpeechActs.IGNORED, snubbed);
            ctx.journal().record(Category.BRAIN, "converse", "gave up waiting");
            return TaskStatus.SUCCESS;
        }).orElse(TaskStatus.RUNNING);
    }

    @Override
    public void cancel(BrainContext ctx) {
        // The record survives us — see the class doc. A walk toward it does not: an interrupted
        // task must not leave the legs owning a move order nobody is watching any more.
        if (walk != null) {
            walk.cancel(ctx);
            walk = null;
        }
    }

    @Override
    public String describe() {
        return "converse";
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Who this body is conversing with. */
    public BeingId other() {
        return other;
    }

    /**
     * How this body credits the encounter's opening if it has to {@link Speech#join join} one —
     * moot once {@link #encounter} is resolved, which is why it, not {@code opening}, is the
     * saved codec's only omission: a restored task re-resolves it through
     * {@link Speech#current()} on its first tick rather than carrying world state of its own.
     */
    public Speech.Opening opening() {
        return opening;
    }

    /**
     * Walks the body to {@code counterpart}'s cell instead of speaking this tick. Held rather than
     * re-issued every tick — a fresh order each tick would restart the route search — and
     * re-created only once the counterpart has moved to a different cell than the one it was
     * issued for.
     *
     * <p>A SUCCESS or FAILED walk is simply dropped: the next tick re-measures distance and
     * perception from scratch rather than trusting a plan that may already be stale, so a
     * counterpart who kept moving, or a route that died, is retried rather than given up on.
     */
    private TaskStatus closeIn(BrainContext ctx, Being counterpart) {
        Pos at = counterpart.pos();
        if (walk == null || walk.x() != at.x() || walk.y() != at.y() || walk.z() != at.z()) {
            walk = new GoTo(at.x(), at.y(), at.z(), Gait.WALK);
        }
        if (walk.tick(ctx) == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        walk = null;
        return TaskStatus.RUNNING;
    }

    /**
     * Faces {@code counterpart} — null means it is not currently perceived, and the sensor's
     * remembered position is left alone rather than chased with a gaze claim.
     */
    private void face(BrainContext ctx, Being counterpart) {
        if (counterpart != null) {
            Pos at = counterpart.pos();
            ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + 1.5, at.z() + 0.5,
                    Gazer.Priority.WORK);
        }
    }

    /**
     * The live percept for whoever the held {@link #encounter} actually says is the other party —
     * {@code other} only stands in before one is resolved. {@code current()} may hand back a
     * record this body was already in with somebody else, and a body swept into that must look at
     * who it is actually talking to, not who it was constructed expecting to. Null when that
     * party is not currently perceived.
     */
    private Being findCounterpart(BrainContext ctx) {
        BeingId counterpart = encounter == null ? other
                : ctx.speech().counterpart(encounter).map(BeingId::of).orElse(other);
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(counterpart)) {
                return being;
            }
        }
        return null;
    }
}
