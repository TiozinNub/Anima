package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import org.jspecify.annotations.Nullable;

/**
 * Keep near a being: stand within {@link #NEAR} of it, and set off again once it is more than
 * {@link #FAR} away. The one followed is the one the body perceives; out of sight and earshot, the
 * body walks to the place the one followed announced, as a group agrees "we'll meet by the lake",
 * and fails there if nobody is in sight. A pet follows its owner the same way.
 *
 * <p>It succeeds once the one followed has stood still beside the follower for {@link #SETTLED_TICKS}:
 * the company is together, and whoever set it running decides what next. The spacing is
 * {@code /anima follow}'s: aim at a cell {@link #NEAR} out on the follower's own side, and re-aim
 * when the one followed has drifted from it.
 */
public final class Follow implements PrimitiveTask {

    public static final double NEAR = 4;
    public static final double FAR = 8;

    /** How far the one followed may drift from the aimed-for cell before the walk is re-aimed. */
    static final double DRIFT = 1.5;

    /** Within this of the meeting place, it has been reached. */
    static final double MEET_WITHIN = 3;

    /** How long the one followed stands still beside the follower before they are together. */
    public static final int SETTLED_TICKS = 200;

    private final BeingId leader;
    private final @Nullable Pos meet;
    private @Nullable Pos aim;
    private boolean issued;
    private @Nullable Pos leaderWas;
    private int settled;

    /**
     * @param meet where the one followed said it would be; null when it said nothing, and then losing
     *             sight of it fails the task
     */
    public Follow(BeingId leader, @Nullable Pos meet) {
        this.leader = leader;
        this.meet = meet;
    }

    public BeingId leader() {
        return leader;
    }

    public @Nullable Pos meet() {
        return meet;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Pos me = ctx.percepts().position();
        Being seen = null;
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(leader)) {
                seen = being;
                break;
            }
        }
        if (aim != null && issued) {
            MoveState state = ctx.actuators().mover().state();
            if (state == MoveState.FAILED) {
                return TaskStatus.FAILED;
            }
            if (state != MoveState.MOVING) {
                aim = null;
            }
        }
        issued = aim != null;
        if (seen == null) {
            if (meet == null || distance(me, meet) <= MEET_WITHIN) {
                return TaskStatus.FAILED; // nobody where they said they would be
            }
            if (!meet.equals(aim)) {
                walk(ctx, meet);
            }
            return TaskStatus.RUNNING;
        }
        Pos at = seen.pos();
        boolean still = leaderWas != null && distance(leaderWas, at) < 0.5;
        leaderWas = at;
        Pos wanted = beside(me, at);
        if (aim == null ? distance(me, at) > FAR : distance(aim, wanted) > DRIFT) {
            walk(ctx, wanted);
        }
        settled = aim == null && still ? settled + 1 : 0;
        return settled >= SETTLED_TICKS ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    private void walk(BrainContext ctx, Pos to) {
        aim = to;
        issued = false; // the legs report on this order from the next tick on
        Pos floor = Standing.floorUnder(ctx, to); // the leader's level, over a slope beside them
        ctx.actuators().mover().moveTo(floor.x(), floor.y(), floor.z());
    }

    /** The cell {@link #NEAR} out from the one followed, on the follower's own side. */
    private static Pos beside(Pos me, Pos at) {
        double dx = me.x() - at.x();
        double dz = me.z() - at.z();
        double length = Math.hypot(dx, dz);
        if (length < 1e-6) {
            dx = 1;
            length = 1;
        }
        return new Pos((int) Math.round(at.x() + dx / length * NEAR), at.y(),
                (int) Math.round(at.z() + dz / length * NEAR));
    }

    private static double distance(Pos a, Pos b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    @Override
    public void cancel(BrainContext ctx) {
        if (aim != null) {
            ctx.actuators().mover().stop();
        }
        aim = null;
    }

    @Override
    public String describe() {
        return "follow " + leader + (meet == null ? "" : ", meeting at (" + meet.x() + ", " + meet.y()
                + ", " + meet.z() + ")");
    }
}
