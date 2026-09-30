package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.Path;
import java.util.List;

/**
 * One flight leg's sprint: to the best of its targets whose route does not leave the body in a
 * pit ({@link Path#trapped}). A body that fled a zombie into a moat it could not climb out of was
 * only shut in with the next thing that fell in (flown on the shelter scene, 2026-09-30).
 *
 * <p>Each target is walked as {@link GoTo} walks it; once the legs have a route, a trapped one is
 * dropped for the next target, a route search apart. None left FAILS, and fight or flight tries a
 * fresh leg.
 */
public final class RunAway implements PrimitiveTask {

    private final List<Pos> targets;
    private int index;
    private GoTo walk;
    private boolean judged;
    private String failure = "";

    /** @param targets where to run, best first; never empty */
    public RunAway(List<Pos> targets) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("nowhere to run");
        }
        this.targets = List.copyOf(targets);
        this.walk = walkTo(this.targets.get(0));
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        TaskStatus status = walk.tick(ctx);
        if (judged || status != TaskStatus.RUNNING) {
            return status;
        }
        Path route = ctx.actuators().mover().route();
        if (route == null) {
            return TaskStatus.RUNNING; // still searching
        }
        judged = true;
        if (!route.trapped()) {
            return TaskStatus.RUNNING;
        }
        walk.cancel(ctx);
        Pos pit = targets.get(index);
        ctx.journal().record(Category.BRAIN, "flee step", "not running to (" + pit.x() + ", "
                + pit.y() + ", " + pit.z() + "): no way back out of there");
        if (++index >= targets.size()) {
            failure = "every way away ends somewhere with no way out";
            return TaskStatus.FAILED;
        }
        walk = walkTo(targets.get(index));
        judged = false;
        return walk.tick(ctx); // ordered now: a flight does not stand still for a tick
    }

    private static GoTo walkTo(Pos target) {
        return new GoTo(target.x(), target.y(), target.z(), Gait.SPRINT).leavingShelter();
    }

    @Override
    public String failureDetail() {
        return failure.isEmpty() ? walk.failureDetail() : describe() + " failed — " + failure;
    }

    @Override
    public void cancel(BrainContext ctx) {
        walk.cancel(ctx);
    }

    /** The walk under way, as {@link GoTo} says it. */
    @Override
    public String describe() {
        return walk.describe();
    }

    // ── continuity: the targets, which one is being run for, and how far that walk has got ──

    public List<Pos> targets() {
        return targets;
    }

    public int index() {
        return index;
    }

    public boolean issued() {
        return walk.issued();
    }

    public boolean judged() {
        return judged;
    }

    /** Puts a saved leg back where it had got to. */
    public RunAway resume(int index, boolean issued, boolean judged) {
        this.index = Math.max(0, Math.min(index, targets.size() - 1));
        this.walk = walkTo(targets.get(this.index)).resume(issued);
        this.judged = judged;
        return this;
    }
}
