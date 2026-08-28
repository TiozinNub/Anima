package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.task.Answer;
import dev.luizloyola.anima.core.brain.task.Converse;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.social.speech.Encounter;
import dev.luizloyola.anima.core.social.speech.Speech;

/**
 * The answering half of a hail — and, since arrival flows into conversation, the resume pull of
 * one already under way: somebody called, or somebody this body already stepped away from is
 * still waiting on it, and this decides whether either is worth doing something about.
 *
 * <p><b>Not a {@code NeedDrive}, deliberately.</b> A drive's bid is its gauge's pressure gated by
 * side, and a hail must move a body that is perfectly CONTENT — otherwise nobody can get a settled
 * Person's attention, including the player. Being called is its own reason, and so is an open chat
 * — pulling exactly as hard as a fresh call rather than fading once walked away from.
 *
 * <p><b>Ignoring is not a behaviour</b> (social foundations §5): it is this instinct losing the
 * bid. Its pressure sits below {@code mind.preempt} on purpose, so a body mid-errand waits for the
 * task boundary and "he was busy" is literally true.
 *
 * <p>Stateless, one instance serving every body — everything it needs arrives in the context.
 */
public final class ConverseInstinct implements Instinct {

    @Override
    public double pressure(BrainContext ctx) {
        // current() is a mutating query — a stale record is closed right here, so this is where
        // the reaping actually happens for a body that never comes back to look.
        return nearestCaller(ctx) != null || ctx.speech().current().isPresent()
                ? ctx.profile().d(ProfileAspect.SOCIAL_HAIL_ANSWER_PRESSURE)
                : 0.0;
    }

    @Override
    public Task root(BrainContext ctx) {
        Being caller = nearestCaller(ctx);
        if (caller != null) {
            return new Answer(caller.id(), caller.pos());
        }
        // The resume pull: no fresh call, but a conversation is still open. The counterpart comes
        // off the port rather than being kept here, so this instinct never needs a self-id.
        // orElseThrow is safe only because the arbiter calls pressure() before root() in the same
        // frozen tick — current() cannot have gone stale between the two calls.
        Encounter current = ctx.speech().current().orElseThrow();
        AgentId otherId = ctx.speech().counterpart(current).orElseThrow();
        return new Converse(BeingId.of(otherId), Speech.Opening.QUIET);
    }

    /**
     * A hail is within earshot by definition, so twice its radius covers a walk around an obstacle
     * and nothing more — unbounded here would let a shout license a journey. The resume branch
     * bounds on {@code social.chat_radius} instead: a hail is far by definition, but returning to
     * a chat this body already stepped away from is near.
     */
    @Override
    public double costTolerance(BrainContext ctx) {
        return 2.0 * ctx.profile().i(nearestCaller(ctx) != null
                ? ProfileAspect.SOCIAL_HAIL_RADIUS : ProfileAspect.SOCIAL_CHAT_RADIUS);
    }

    @Override
    public String describe() {
        return "converse";
    }

    /** The closest body currently calling, or null when nobody is. */
    private static Being nearestCaller(BrainContext ctx) {
        Being best = null;
        for (Being being : ctx.percepts().beings()) {
            if (being.hailing() && (best == null || being.distance() < best.distance())) {
                best = being;
            }
        }
        return best;
    }
}
