package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.task.Answer;
import dev.luizloyola.anima.core.brain.task.Task;

/**
 * The answering half of a hail: somebody called, and this decides whether that is worth doing
 * something about.
 *
 * <p><b>Not a {@code NeedDrive}, deliberately.</b> A drive's bid is its gauge's pressure gated by
 * side, and a hail must move a body that is perfectly CONTENT — otherwise nobody can get a settled
 * Person's attention, including the player. Being called is its own reason.
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
        return nearestCaller(ctx) == null
                ? 0.0
                : ctx.profile().d(ProfileAspect.SOCIAL_HAIL_ANSWER_PRESSURE);
    }

    @Override
    public Task root(BrainContext ctx) {
        Being caller = nearestCaller(ctx);
        return new Answer(caller.id(), caller.pos());
    }

    /**
     * A hail is within earshot by definition, so twice its radius covers a walk around an obstacle
     * and nothing more. Unbounded here would let a shout license a journey.
     */
    @Override
    public double costTolerance(BrainContext ctx) {
        return 2.0 * ctx.profile().i(ProfileAspect.SOCIAL_HAIL_RADIUS);
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
