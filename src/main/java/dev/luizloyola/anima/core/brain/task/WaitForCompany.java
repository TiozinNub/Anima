package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Set;

/**
 * Stand until every one of these beings is perceived within a radius, or a time has passed —
 * whichever comes first, and both are a success: one who fell behind, went to eat or froze in an
 * unloaded chunk does not hold the rest forever. What a group does at a stop before going on.
 *
 * <p>Saved by the ticks left, so a restart does not start the wait over.
 */
public final class WaitForCompany implements PrimitiveTask {

    private final Set<BeingId> whom;
    private final int radius;
    private int remaining;

    public WaitForCompany(Set<BeingId> whom, int radius, int ticks) {
        this.whom = Set.copyOf(whom);
        this.radius = radius;
        this.remaining = Math.max(0, ticks);
    }

    public Set<BeingId> whom() {
        return whom;
    }

    public int radius() {
        return radius;
    }

    public int remaining() {
        return remaining;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (together(ctx.percepts().position(), ctx.percepts().beings()) || remaining <= 0) {
            return TaskStatus.SUCCESS;
        }
        remaining--;
        return TaskStatus.RUNNING;
    }

    private boolean together(Pos me, List<Being> beings) {
        int near = 0;
        for (Being being : beings) {
            if (whom.contains(being.id())
                    && Math.hypot(being.pos().x() - me.x(), being.pos().z() - me.z()) <= radius) {
                near++;
            }
        }
        return near >= whom.size();
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "wait for " + whom.size() + " to catch up (" + remaining + " ticks at most)";
    }
}
