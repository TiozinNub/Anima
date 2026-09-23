package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.social.speech.Speech;

/**
 * Go and be near somebody — company's lonely end, and the only thing in rung 4 that OPENS a hail.
 *
 * <p><b>It targets what is perceived, never what is remembered.</b> Nothing is known that was not
 * perceived, so a lonely body with nobody in sight or earshot — or nobody who looks free to talk —
 * FAILS here and the arbiter's fail-cooldown paces the retry. Searching for people beyond perception is curiosity's job and is
 * deliberately absent — see the voice-and-hail design.
 *
 * <p><b>The hail needs a reason, not a cooldown</b> (decision: Luiz). Two hold in rung 4: not
 * knowing that body, and being lonely near one we do know. Either is spent by
 * {@code Percepts.calledLately}, so the guardrail stays a reason — "and I have not tried lately" —
 * rather than becoming a rate limit.
 *
 * <p><b>TARGETING spends the mark, not shouting.</b> A body inside the hearing radius is never
 * shouted at, so a mark stamped by the shout alone would never be stamped for a neighbour — and
 * this task would pick the same one, walk the two steps to its cell, SUCCEED, and be granted
 * again next tick, forever. Marking whoever is selected is what makes the design's own words true:
 * the same mark that stops a second shout stops a second walk.
 *
 * <p><b>Both branches journal.</b> This is the caller's only trace — the hearer narrates the axis
 * flip, but a hail that nothing was in range to hear would otherwise be indistinguishable from no
 * hail at all. Whoever is named comes through {@code knownAs}, so the record never puts a name to
 * somebody who has not given one.
 *
 * <p><b>Arrival flows straight into the conversation the walk was for.</b> Once {@link #walk}
 * SUCCEEDS this swaps to a {@link Converse} delegate — crediting the opening as {@code I_HAILED}
 * or {@code QUIET} to match whichever branch above actually ran — and forwards every tick to it
 * from then on, the same delegate pattern the walk itself already used.
 */
public final class SeekCompany implements PrimitiveTask {

    private BeingId target;
    private boolean hailed;
    private GoTo walk;
    private Converse converse;

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (converse != null) {
            return converse.tick(ctx);
        }
        if (walk == null) {
            Being being = nearest(ctx);
            if (being == null) {
                return TaskStatus.FAILED;
            }
            target = being.id();
            // RECORDED, and the two branches read differently on purpose: the arbiter's own
            // "take over" line is identical whether this shouted or walked over in silence, so
            // without this the one decision shouldHail makes leaves no trace — and a hail nobody
            // was in range to hear could not be told from no hail at all.
            hailed = shouldHail(ctx, being);
            if (hailed) {
                ctx.actuators().voice().hail(being.id());
                ctx.journal().record(Category.BRAIN, "seek_people",
                        "called out to " + being.knownAs());
            } else {
                ctx.actuators().voice().reachedOut(being.id());
                ctx.journal().record(Category.BRAIN, "seek_people",
                        "went over to " + being.knownAs());
            }
            Pos at = being.pos();
            walk = new GoTo(at.x(), at.y(), at.z(), Gait.WALK);
        }
        TaskStatus status = walk.tick(ctx);
        if (status != TaskStatus.SUCCESS) {
            return status;
        }
        walk = null; // spent — the conversation is what this walk was for
        converse = new Converse(target, hailed ? Speech.Opening.I_HAILED : Speech.Opening.QUIET);
        return converse.tick(ctx);
    }

    @Override
    public void cancel(BrainContext ctx) {
        if (walk != null) {
            walk.cancel(ctx);
        }
        if (converse != null) {
            converse.cancel(ctx);
        }
    }

    /** Walking over counts as much as talking: the seat reads it, and this is the lonely half. */
    @Override
    public boolean converses() {
        return true;
    }

    @Override
    public String describe() {
        // Once handed off, the executor's own readout should say what the body is actually doing.
        return converse != null ? converse.describe() : "seek company";
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /** Who was picked, or null while a target is still to be chosen. */
    public BeingId target() {
        return target;
    }

    /** Whether that target was shouted at rather than walked over to in silence. */
    public boolean hailed() {
        return hailed;
    }

    /**
     * The walk under way, or null before a target is chosen and after it is spent into
     * {@link #converse}. Real progress, not a re-derivable one: a reload that lost it would pick a
     * target again, and the mark that was spent on the first one now points the body at somebody
     * else.
     */
    public GoTo walk() {
        return walk;
    }

    /** The conversation this errand handed off to, or null before the walk has succeeded. */
    public Converse converse() {
        return converse;
    }

    /** Puts the body back on the leg it had already ordered, toward the target it already picked. */
    public SeekCompany resume(BeingId target, boolean hailed, GoTo walk) {
        this.target = target;
        this.hailed = hailed;
        this.walk = walk;
        return this;
    }

    /**
     * Puts the body back into the conversation it had already handed off to. The live
     * {@link dev.luizloyola.anima.core.social.speech.Encounter} is not carried — see
     * {@link Converse#opening()} — so this reconstructs a fresh delegate exactly as the tick past
     * the walk's SUCCESS would.
     */
    public SeekCompany resumeConverse(BeingId target, boolean hailed) {
        this.target = target;
        this.hailed = hailed;
        this.converse = new Converse(target, hailed ? Speech.Opening.I_HAILED : Speech.Opening.QUIET);
        return this;
    }

    /**
     * Whether calling out would say anything walking over does not. Inside the hearing radius an
     * ordinary voice already carries — which is the same test {@code social.hail_radius} is
     * declared against.
     *
     * <p>It decides the SOUND and nothing else: the per-target mark is spent on either answer.
     */
    private static boolean shouldHail(BrainContext ctx, Being target) {
        if (target.distance() <= ctx.profile().i(ProfileAspect.SENSES_HEARING_RADIUS)) {
            return false;
        }
        // No `calledLately` check here — `nearest` already refused a called target, so reaching
        // this point means the reason is intact. Checking twice would read as two guardrails.
        return true; // a stranger, or a friend worth calling: both intents want the same shout
    }

    /**
     * The closest minded body worth walking to, or null when there is none.
     *
     * <p>Somebody already called is skipped — the same mark that stops a second shout stops a
     * second walk, so a body does not trudge back to whoever it just gave up on. The walk already
     * under way is unaffected: the target is chosen once, on the first tick, and cached.
     *
     * <p>A body a player is driving is fair game since rung 7 gave players a reply. It was skipped
     * before that for a reason worth keeping in mind if the menu ever goes away: the first live
     * test had settlers crossing a field to stand in front of a player and wait out the clock.
     */
    private static Being nearest(BrainContext ctx) {
        Being best = null;
        for (Being being : ctx.percepts().beings()) {
            if (!being.kind().minded() || !approachable(being)
                    || ctx.percepts().calledLately(being.id())) {
                continue;
            }
            if (best == null || being.distance() < best.distance()) {
                best = being;
            }
        }
        return best;
    }

    /**
     * Whether they look free to talk: arms idle, and not running. {@link Being.Activity#IDLE} is
     * the approachable state the social spec named and nothing read until 2026-09-23 — a lonely
     * body walked up to somebody mid-chop, greeted, asked a name, and waited out its patience on
     * an answer that waits for the worker's task boundary. Read off the body, never the brain, so
     * a worker standing still between swings still looks free.
     */
    private static boolean approachable(Being being) {
        return being.activity() == Being.Activity.IDLE
                && being.locomotion() != Being.Locomotion.SPRINTING;
    }
}
