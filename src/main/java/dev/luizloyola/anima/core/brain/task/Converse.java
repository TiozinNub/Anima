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
 * <p><b>Taking over means walking into range first — once.</b> A body handed this from beyond
 * {@code social.chat_radius} — the resume pull can grant it across an open field — closes the
 * distance before a word is said; see {@link #closeIn}. Two rules bound that walk, both from the
 * first live client test, where a settler tailed a moving counterpart across a field and stood
 * waiting out the patience clock:
 *
 * <ul>
 *   <li><b>No chase after contact.</b> Once the two have been within chat range together, this
 *       body never orders another step toward them. Somebody who then walks away is DECLINING,
 *       and following them is the one reading of it that is never right.</li>
 *   <li><b>Give up before contact.</b> While they have never been in range, each leg is measured:
 *       two in a row that close no distance end the errand. Follow a bit, then accept they are
 *       busy.</li>
 * </ul>
 *
 * <p>Either ending writes the same IGNORED line the snub clock writes, so the record closes as
 * trailed off rather than lingering open for staleness to sweep.
 *
 * <p><b>None of that state is saved.</b> The codec carries {@code (other, opening)} and nothing
 * else: a restored Converse re-measures distance, re-resolves its encounter and starts its own
 * counters on the first tick, which is the same thing it does whenever the arbiter hands it back.
 */
public final class Converse implements PrimitiveTask {

    /** Legs that closed no distance before this body accepts they are walking somewhere else. */
    private static final int FRUITLESS_LIMIT = 2;

    /** Half-range so {@code now - stamp} on a never-set clock cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final BeingId other;
    private final Speech.Opening opening;
    private Encounter encounter;
    /** The walk toward the counterpart while out of chat range — see {@link #closeIn}. Never saved. */
    private GoTo walk;
    /** Whether the two have ever stood within chat range together — the no-chase latch. */
    private boolean everInRange;
    /** When they went out of range after that, or {@link #NEVER} — the trailed-off clock. */
    private long distancedSince = NEVER;
    /** Their distance when the current leg was ordered, or NaN before any leg. */
    private double walkedFrom = Double.NaN;
    /** Consecutive legs that ended no nearer to them than they started. */
    private int fruitless;

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
        boolean inRange = counterpart != null
                && counterpart.distance() <= ctx.profile().i(ProfileAspect.SOCIAL_CHAT_RADIUS);
        if (inRange) {
            everInRange = true;
            distancedSince = NEVER; // they came back; the trailed-off clock never ran
        } else if (everInRange) {
            return trailOff(ctx);
        } else if (counterpart != null) {
            return closeIn(ctx, counterpart);
        }
        // Unperceived and never yet in range: nothing to walk to and nothing to read as leaving.
        // Speaking on anyway is what lets a conversation carry on through a wall.
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
     * Walks the body to {@code counterpart}'s cell instead of speaking this tick — the PRE-contact
     * branch only. Held rather than re-issued every tick — a fresh order each tick would restart
     * the route search — and re-created only once the counterpart has moved to a different cell
     * than the one it was issued for.
     *
     * <p>A SUCCESS or FAILED walk is simply dropped: the next tick re-measures distance and
     * perception from scratch rather than trusting a plan that may already be stale, so a
     * counterpart who kept moving, or a route that died, is retried rather than given up on.
     *
     * <p><b>But not forever.</b> Every re-issue is where the following is priced, because that is
     * the only honest place: a leg that ended, or a target cell that moved, has run its course,
     * while distance sampled mid-leg is just the body's own stride. Two legs in a row that leave
     * the gap no smaller and this stops — somebody walking away at walking pace is somebody with
     * an errand of their own.
     */
    private TaskStatus closeIn(BrainContext ctx, Being counterpart) {
        Pos at = counterpart.pos();
        if (walk == null || walk.x() != at.x() || walk.y() != at.y() || walk.z() != at.z()) {
            if (!Double.isNaN(walkedFrom)) {
                if (counterpart.distance() < walkedFrom) {
                    fruitless = 0; // ground gained — the following is working
                } else if (++fruitless >= FRUITLESS_LIMIT) {
                    return letGo(ctx, "they were headed somewhere else");
                }
            }
            walk = new GoTo(at.x(), at.y(), at.z(), Gait.WALK);
            walkedFrom = counterpart.distance();
        }
        if (walk.tick(ctx) == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        walk = null;
        return TaskStatus.RUNNING;
    }

    /**
     * The POST-contact branch: they were here, and now they are not. No walk is ordered and no
     * word is said — a body that has already been spoken to and has moved off is declining, and
     * chasing that is the one reading of it that is never right. Waited out for
     * {@code social.patience_ticks}, the same clock an unanswered question gets, and then closed
     * with the same IGNORED line, because it is the same fact: nothing came back.
     */
    private TaskStatus trailOff(BrainContext ctx) {
        if (walk != null) {
            walk.cancel(ctx);
            walk = null;
        }
        long now = ctx.percepts().time();
        if (distancedSince == NEVER) {
            distancedSince = now;
            return TaskStatus.RUNNING;
        }
        if (now - distancedSince <= ctx.profile().i(ProfileAspect.SOCIAL_PATIENCE_TICKS)) {
            return TaskStatus.RUNNING;
        }
        return letGo(ctx, "read the distancing and let them go");
    }

    /** Ends the errand the way a snub ends: the record says IGNORED, the journal says why. */
    private TaskStatus letGo(BrainContext ctx, String why) {
        ctx.speech().system(encounter, SpeechActs.IGNORED,
                // Who the RECORD says the other party is — `other` only stands in until one is
                // resolved, exactly as findCounterpart reads it.
                ctx.speech().counterpart(encounter).orElseGet(other::asPerson));
        ctx.journal().record(Category.BRAIN, "converse", why);
        return TaskStatus.SUCCESS;
    }

    /**
     * Faces {@code counterpart} — null means it is not currently perceived, and the sensor's
     * remembered position is left alone rather than chased with a gaze claim.
     */
    private void face(BrainContext ctx, Being counterpart) {
        if (counterpart != null) {
            Pos at = counterpart.pos();
            // Their own eye height, not a constant: a conversation with anything shorter or taller
            // than a person otherwise reads as talking over its head.
            ctx.actuators().gazer().lookAt(at.x() + 0.5, at.y() + counterpart.eyeHeight(),
                    at.z() + 0.5, Gazer.Priority.WORK);
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
