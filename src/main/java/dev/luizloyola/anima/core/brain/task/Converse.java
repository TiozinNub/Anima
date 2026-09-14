package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.AgentId;
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
import dev.luizloyola.anima.core.social.speech.Picker;
import dev.luizloyola.anima.core.social.speech.Speech;
import dev.luizloyola.anima.core.social.speech.SpeechActs;
import dev.luizloyola.anima.core.social.speech.Utterance;
import java.util.Optional;

/**
 * Participating in a conversation is a task like any other — it holds the body in place,
 * turns the head, and takes turns on this brain's own ticks. Cancel leaves the record OPEN
 * on purpose: interruption is not rudeness, and the other side's patience or the staleness
 * gap ends what we walked away from.
 *
 * <p><b>A busy body is not joined.</b> Where the counterpart is already in somebody else's record
 * this FAILS on its first tick, before a word or a step — one open conversation per body, see
 * {@link Speech#join}.
 *
 * <p><b>Taking over means walking into range first — once.</b> A body handed this from beyond
 * {@code social.chat_radius} — the resume pull can grant it across an open field — closes the
 * distance before a word is said; see {@link #closeIn}. Two rules bound that walk, both from the
 * first live client test, where a settler tailed a moving counterpart across a field and stood
 * waiting out the patience clock:
 *
 * <ul>
 *   <li><b>No chase after contact.</b> Once the two have been within chat range together — or the
 *       other party has said anything into the record, which is how this survives a preemption;
 *       see {@link #contactMade} — this body never orders another step toward them. Somebody who
 *       then walks away is DECLINING, and following them is the one reading of it that is never
 *       right.</li>
 *   <li><b>Give up before contact.</b> While they have never been in range, each leg is measured:
 *       two in a row that close no distance end the errand. Follow a bit, then accept they are
 *       busy.</li>
 * </ul>
 *
 * <p>Either ending writes the same IGNORED line the snub clock writes, and stops the legs on its
 * way out, so the record closes as trailed off rather than lingering open for staleness to sweep
 * while the body walks on toward a cell nobody is watching.
 *
 * <p><b>None of that state is saved, and the durable half is not state at all.</b> The codec
 * carries {@code (other, opening)} and nothing else. Whether contact has HAPPENED is read off the
 * record ({@link #contactMade}) rather than latched in a field, because a field is per-grant: a
 * preemption or a reload builds a fresh Converse, and a latch that came back false would re-arm
 * the very chase this class exists to stop. Everything else — the trailed-off clock, the leg
 * counter — is a reading of what just happened in front of the body, and starting those over is
 * exactly right on a fresh grant.
 */
public final class Converse implements PrimitiveTask {

    /**
     * The widest a body may sit on a line beyond {@link Picker#REPLY_GRACE_TICKS}, rolled fresh
     * for every line that lands.
     *
     * <p><b>Because a metronome does not read as alive.</b> The grace is a uniform floor, so two
     * bodies answering each other land every line on exactly the same beat — a conversation that
     * ticks. Irregularity is what a watching player reads as somebody thinking about it.
     *
     * <p>And each side rolls its OWN number from its own stream, which is the second half: on a
     * beat where both parties are eligible the tie used to be settled by nothing more than the
     * world's entity order, so whoever ticked first always spoke. Independent rolls dissolve that.
     */
    public static final int JITTER_TICKS = 20;

    /** Legs that closed no distance before this body accepts they are walking somewhere else. */
    private static final int FRUITLESS_LIMIT = 2;

    /** Half-range so {@code now - stamp} on a never-set clock cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final BeingId other;
    private final Speech.Opening opening;
    private Encounter encounter;
    /** The walk toward the counterpart while out of chat range — see {@link #closeIn}. Never saved. */
    private GoTo walk;
    /** Whether the two have stood within chat range together on THIS grant — the cheap half of
     *  {@link #contactMade}, which is what anything else should ask. */
    private boolean everInRange;
    /** When they went out of range after that, or {@link #NEVER} — the trailed-off clock. */
    private long distancedSince = NEVER;
    /** Their distance when {@link #walk} was ordered. Meaningful only while that walk is live —
     *  {@link #dropWalk} clears the two together, so a leg and its yardstick never outlive one
     *  another. */
    private double walkedFrom = Double.NaN;
    /** Consecutive legs that ended no nearer to them than they started. */
    private int fruitless;
    /** Transcript length {@link #jitter} was last rolled against, so one line buys one roll. */
    private int lineCount = -1;
    /** Ticks this body waits on top of the grace floor before its next line — see {@link #JITTER_TICKS}. */
    private int jitter;

    public Converse(BeingId other, Speech.Opening opening) {
        this.other = other;
        this.opening = opening;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Speech speech = ctx.speech();
        if (encounter == null) {
            Optional<Encounter> mine = speech.current();
            if (mine.isEmpty()) {
                mine = speech.join(other, opening);
            }
            if (mine.isEmpty()) {
                // They are mid-conversation with somebody else, and a second record against a busy
                // body is exactly the churn one-conversation-per-body exists to stop. FAILED rather
                // than SUCCESS so the arbiter's fail cooldown paces the retry; the spent hail mark
                // already stops this body shouting again in the meantime.
                dropWalk(ctx); // nothing can be running this early, and nothing may be after
                ctx.journal().record(Category.BRAIN, "converse", "they were already talking");
                return TaskStatus.FAILED;
            }
            encounter = mine.get();
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
        } else if (contactMade(ctx)) {
            return trailOff(ctx);
        } else if (counterpart != null) {
            return closeIn(ctx, counterpart);
        }
        // Unperceived and never yet met: nothing to walk to and nothing to read as leaving.
        // Speaking on anyway is what lets a conversation carry on through a wall.
        // Within range now, or the counterpart dropped out of sight — either way the walk is
        // spent; a live order left behind would keep the legs moving toward a stale cell.
        dropWalk(ctx);
        face(ctx, counterpart);
        // The floor first, then this body's own roll on top of it. A blocked roll falls THROUGH to
        // the snub check below rather than returning: waiting on somebody is a different clock, and
        // making it wait on a jitter of ours would let a snubber buy time by our own hesitation.
        if (speech.maySpeak(encounter) && beatElapsed(ctx)) {
            Chooser.Line line = speech.chooser().choose(ctx, speech.turn(encounter));
            if (line != null) {
                speech.say(encounter, line);
                ctx.journal().record(Category.BRAIN, "converse", "said " + line.act().key());
                return encounter.closed() ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
            }
        }
        // A silent tick — by the beat, or by a chooser holding its tongue — falls through to the
        // clocks that run on THEM. Returning early on the chooser's silence stalled them: a body
        // that had asked once and was rightly waiting (Turn#awaiting) never called the snub, and
        // the record sat open until staleness swept it (found 2026-09-13).
        if (speech.lapsedFarewell(encounter)) {
            // Nobody answered the goodbye. Not a snub: the last line already says why it ended.
            speech.close(encounter);
            ctx.journal().record(Category.BRAIN, "converse", "left without an answer");
            return TaskStatus.SUCCESS;
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
        dropWalk(ctx);
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
     * <p><b>But not forever.</b> The one place following is priced is a leg the COUNTERPART
     * outran — they moved to another cell, so a live walk has to be replaced, and the distance
     * then says whether the last one bought anything. Two of those in a row leaving the gap no
     * smaller and this stops: somebody walking away at walking pace is somebody with an errand of
     * their own.
     *
     * <p>Deliberately NOT priced: a leg that ended by itself. A route that failed, or legs an
     * arbiter took, says nothing about whether they are walking away from us, and counting it
     * would end conversations over terrain. Distance sampled mid-leg is not priced either — that
     * is just this body's own stride.
     */
    private TaskStatus closeIn(BrainContext ctx, Being counterpart) {
        Pos at = counterpart.pos();
        if (walk == null || walk.x() != at.x() || walk.y() != at.y() || walk.z() != at.z()) {
            if (walk != null) {
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
        dropWalk(ctx); // the leg ended on its own; the next tick orders a fresh one, unpriced
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
        dropWalk(ctx);
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

    /**
     * Ends the errand the way a snub ends: the legs stop, the record says IGNORED, the journal
     * says why.
     *
     * <p>The teardown lives HERE rather than in each caller because forgetting it restages the
     * bug this whole branch fixes — a task that returns SUCCESS still owning a move order leaves
     * the body walking after somebody it has just decided to let go of.
     */
    private TaskStatus letGo(BrainContext ctx, String why) {
        dropWalk(ctx);
        ctx.speech().system(encounter, SpeechActs.IGNORED, subject(ctx));
        ctx.journal().record(Category.BRAIN, "converse", why);
        return TaskStatus.SUCCESS;
    }

    /** Lets go of a live leg and the yardstick measuring it — never one without the other. */
    private void dropWalk(BrainContext ctx) {
        if (walk != null) {
            walk.cancel(ctx);
            walk = null;
        }
        walkedFrom = Double.NaN;
    }

    /**
     * Whether this body's own beat has elapsed: {@link Picker#REPLY_GRACE_TICKS}, which
     * {@link Speech#maySpeak} has already insisted on, plus a fresh roll of up to
     * {@link #JITTER_TICKS} for every line that lands.
     *
     * <p>Only SPEAKING waits on this. Facing, walking, trailing off and the snub clock all read the
     * world rather than this body's hesitation, and are untouched.
     *
     * <p>Measured off the last line somebody SAID, exactly as the floor it extends is — a SYSTEM
     * line is the world reporting on the conversation, not a turn in it. An empty record has no
     * beat to wait out: the opening line is immediate.
     */
    private boolean beatElapsed(BrainContext ctx) {
        int lines = encounter.transcript().size();
        if (lines != lineCount) {
            lineCount = lines;
            jitter = ctx.random().nextInt(JITTER_TICKS + 1);
        }
        return Picker.lastSpoken(encounter)
                .map(line -> ctx.percepts().time()
                        >= line.tick() + Picker.REPLY_GRACE_TICKS + jitter)
                .orElse(true);
    }

    /**
     * Whether these two have MET: within chat range on this grant, or — the durable half — the
     * other party having actually said something into this record.
     *
     * <p>Read off the transcript instead of held in a field, because a field is per-grant. A
     * preemption or a reload builds a fresh {@code Converse}, and a latch that came back false
     * would re-arm a bounded chase after contact was already made. The record outlives both, and
     * asking it costs nothing new on disk.
     *
     * <p>A SYSTEM line is nobody's, so it never counts. Neither does a HAIL, and that is the
     * subtle one: {@code THEY_HAILED} prefills a hail AUTHORED by the counterpart, but a shout is
     * what a body does when it is too far away to talk — counting it as contact would stop
     * {@code Answer} closing the last steps toward whoever called across a field, which is the one
     * walk that is always right.
     */
    private boolean contactMade(BrainContext ctx) {
        if (everInRange) {
            return true;
        }
        AgentId them = subject(ctx);
        for (Utterance line : encounter.transcript()) {
            if (!line.system() && them.equals(line.author())
                    && !SpeechActs.HAIL.key().equals(line.act())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Who the RECORD says the other party is — {@code other} only stands in until one is
     * resolved, exactly as {@link #findCounterpart} reads it.
     */
    private AgentId subject(BrainContext ctx) {
        return ctx.speech().counterpart(encounter).orElseGet(other::asPerson);
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
