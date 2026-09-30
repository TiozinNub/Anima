package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.Waypoint;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A sprint to the cell inside a remembered shelter's door that judges its route before it commits
 * to it (shelter spec, rung 5). The straight line chose the shelter; the route the legs actually
 * plan decides whether to go:
 *
 * <ul>
 *   <li>it must get there: a flight neither bridges nor scales, so a shelter across a ravine has
 *       no route, however near it looks (Luiz, 2026-09-30);</li>
 *   <li>it must be no longer than {@link #REACH};</li>
 *   <li>no threat may get to any cell of it first, or it cuts the body off on the way;</li>
 *   <li>the body must be at the end {@link ShelterRace#DOOR_MARGIN_TICKS} ahead of every threat.</li>
 * </ul>
 *
 * <p>A route that fails stops the legs at once and FAILS, and the shelter is left alone for a
 * while so the next leg does not ask again: a minute when there is no way there, two seconds when
 * a threat is in the way, which changes as it moves. The flight step then runs as it would have.
 */
public final class RunToShelter implements PrimitiveTask {

    /** How far a shelter may be, by the way there, to be run for: 64 blocks (Luiz, 2026-09-30). */
    public static final double REACH = 64.0;

    /** How long a shelter with no way there, or too long a one, is left alone: a minute. */
    static final int NO_WAY_TICKS = 1_200;

    /** How long a shelter a threat stands in the way of is left alone: two seconds. */
    static final int BLOCKED_TICKS = 40;

    private final Pos in;
    private final GoTo walk;
    private boolean judged;
    private String failure = "";

    public RunToShelter(int x, int y, int z) {
        this.in = new Pos(x, y, z);
        this.walk = new GoTo(x, y, z, Gait.SPRINT);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        TaskStatus status = walk.tick(ctx);
        if (status == TaskStatus.FAILED) {
            failure = walk.failureDetail();
            return status;
        }
        if (judged || status != TaskStatus.RUNNING) {
            return status;
        }
        Path route = ctx.actuators().mover().route();
        if (route == null) {
            return TaskStatus.RUNNING; // still searching
        }
        judged = true;
        Refusal refusal = refusal(ctx, route);
        if (refusal == null) {
            return TaskStatus.RUNNING;
        }
        walk.cancel(ctx);
        ctx.knowledge().avoid(PoiKind.SHELTER, in, ctx.percepts().time() + refusal.aloneFor());
        failure = describe() + " refused — " + refusal.why();
        // Said here: a flight step that falls through to its next way journals nothing of this.
        ctx.journal().record(Category.BRAIN, "run for shelter", "refused: " + refusal.why());
        return TaskStatus.FAILED;
    }

    /** Why a route will not do, and how long to leave the shelter alone for it. */
    private record Refusal(String why, int aloneFor) {
    }

    /** Why this route will not do, or null when it will. */
    private @Nullable Refusal refusal(BrainContext ctx, Path route) {
        if (!route.reachedGoal()) {
            return new Refusal("no way there without building", NO_WAY_TICKS);
        }
        Combatant me = ctx.percepts().selfAsCombatant().orElse(null);
        if (me == null || me.pace() <= 0.0) {
            return null; // nothing to race with: the straight line already passed
        }
        List<ShelterRace.Runner> runners = ShelterRace.pressing(ctx, me.pace());
        Pos at = ctx.percepts().position();
        double length = 0.0;
        double lead = Double.POSITIVE_INFINITY;
        List<Waypoint> ways = route.waypoints();
        for (int i = 0; i < ways.size(); i++) {
            Waypoint way = ways.get(i);
            Pos cell = new Pos(way.x(), way.y(), way.z());
            length += ShelterRace.distance(at, cell);
            at = cell;
            lead = ShelterRace.lead(runners, cell, length / me.pace());
            if (lead < 0.0) {
                return new Refusal(String.format(Locale.ROOT, "something gets to (%d, %d, %d) first",
                        cell.x(), cell.y(), cell.z()), BLOCKED_TICKS);
            }
        }
        if (length > REACH) {
            return new Refusal(String.format(Locale.ROOT, "too far by the way there, %.0f blocks",
                    length), NO_WAY_TICKS);
        }
        if (lead < ShelterRace.DOOR_MARGIN_TICKS) {
            return new Refusal("something gets to the door first", BLOCKED_TICKS);
        }
        return null;
    }

    @Override
    public String failureDetail() {
        return failure.isEmpty() ? describe() + " failed" : failure;
    }

    @Override
    public void cancel(BrainContext ctx) {
        walk.cancel(ctx);
    }

    @Override
    public String describe() {
        return "run for shelter at (" + in.x() + ", " + in.y() + ", " + in.z() + ")";
    }

    // ── continuity: what it is, whether the walk was ordered, and whether its route passed ──

    public Pos in() {
        return in;
    }

    public boolean issued() {
        return walk.issued();
    }

    public boolean judged() {
        return judged;
    }

    /** Puts a saved run back where it had got to. */
    public RunToShelter resume(boolean issued, boolean judged) {
        walk.resume(issued);
        this.judged = judged;
        return this;
    }
}
