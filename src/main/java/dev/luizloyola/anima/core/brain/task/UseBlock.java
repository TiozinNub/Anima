package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Use the block at a cell with the empty hand ({@link dev.luizloyola.anima.core.brain.act.Hand}):
 * look at it, one handling beat, then the use. SUCCEEDS when the world changed and FAILS when it did
 * not — out of reach, or nothing there any more to use — so a plan built on an old look finds out.
 */
public final class UseBlock implements PrimitiveTask {

    private final Pos target;
    private final Pause pause = new Pause();

    public UseBlock(int x, int y, int z) {
        this.target = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.actuators().gazer().lookAt(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5,
                Gazer.Priority.WORK);
        if (pause.idle()) {
            pause.start(ctx.profile().i(ProfileAspect.HANDLING_STACK_TICKS));
        }
        if (!pause.elapsed()) {
            return TaskStatus.RUNNING;
        }
        return ctx.actuators().hand().use(target) ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing is held: the use lands on one tick or not at all.
    }

    @Override
    public String describe() {
        return "use (" + target.x() + ", " + target.y() + ", " + target.z() + ")";
    }

    public Pos target() {
        return target;
    }

    public int pauseTicks() {
        return pause.remaining();
    }

    /** Puts a reload back mid-beat. */
    public UseBlock resume(int pauseTicks) {
        pause.restore(pauseTicks);
        return this;
    }
}
