package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.social.speech.Chooser;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechActs;

/**
 * Participating in a conversation is a task like any other — it holds the body in place,
 * turns the head, and takes turns on this brain's own ticks. Cancel leaves the record OPEN
 * on purpose: interruption is not rudeness, and the other side's patience or the staleness
 * gap ends what we walked away from.
 */
public final class Converse implements PrimitiveTask {

    private final BeingId other;
    private final Speech.Opening opening;
    private Encounter encounter;

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
        face(ctx);
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
        // Nothing: the record survives us — see the class doc.
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
     * Faces whoever the held {@link #encounter} actually says is the other party — {@code other}
     * only stands in before one is resolved. {@code current()} may hand back a record this body
     * was already in with somebody else, and a body swept into that must look at who it is
     * actually talking to, not who it was constructed expecting to.
     */
    private void face(BrainContext ctx) {
        BeingId counterpart = encounter == null ? other
                : ctx.speech().counterpart(encounter).map(BeingId::of).orElse(other);
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(counterpart)) {
                Pos at = being.pos();
                ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + 1.5, at.z() + 0.5,
                        Gazer.Priority.WORK);
                return;
            }
        }
    }
}
