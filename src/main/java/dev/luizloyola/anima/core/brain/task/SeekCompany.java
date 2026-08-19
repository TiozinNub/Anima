package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.Gait;

/**
 * Go and be near somebody — company's lonely end, and the only thing in rung 4 that OPENS a hail.
 *
 * <p><b>It targets what is perceived, never what is remembered.</b> Nothing is known that was not
 * perceived, so a lonely body with nobody in sight or earshot FAILS here and the arbiter's
 * fail-cooldown paces the retry. Searching for people beyond perception is curiosity's job and is
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
 */
public final class SeekCompany implements PrimitiveTask {

    private GoTo walk;

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (walk == null) {
            Being target = nearest(ctx);
            if (target == null) {
                return TaskStatus.FAILED;
            }
            if (shouldHail(ctx, target)) {
                ctx.actuators().voice().hail(target.id());
            } else {
                ctx.actuators().voice().reachedOut(target.id());
            }
            Pos at = target.pos();
            walk = new GoTo(at.x(), at.y(), at.z(), Gait.WALK);
        }
        return walk.tick(ctx);
    }

    @Override
    public void cancel(BrainContext ctx) {
        if (walk != null) {
            walk.cancel(ctx);
        }
    }

    @Override
    public String describe() {
        return "seek company";
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * The walk under way, or null while the target is still to be chosen. Real progress, not a
     * re-derivable one: a reload that lost it would pick a target again, and the mark that was
     * spent on the first one now points the body at somebody else.
     */
    public GoTo walk() {
        return walk;
    }

    /** Puts the body back on the leg it had already ordered. */
    public SeekCompany resume(GoTo walk) {
        this.walk = walk;
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
     */
    private static Being nearest(BrainContext ctx) {
        Being best = null;
        for (Being being : ctx.percepts().beings()) {
            if (!being.kind().minded() || ctx.percepts().calledLately(being.id())) {
                continue;
            }
            if (best == null || being.distance() < best.distance()) {
                best = being;
            }
        }
        return best;
    }
}
