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
 *
 * <p><b>Homeward</b>, a walk in for the night by a body not away on purpose (Luiz, 2026-10-21): the
 * reach is the night's ({@link NightShelterStep#reach}) rather than {@link #REACH}, and a route that
 * stops short of the door is a first leg, not a refusal — a long walk is planned leg by leg, and the
 * rest is counted in a straight line. From nightfall on the walk left is weighed again every tick,
 * and one longer than {@link NightShelterStep#pressOn} stops for a dig.
 */
public final class RunToShelter implements PrimitiveTask {

    /** How far a shelter may be, by the way there, to be run for: 64 blocks (Luiz, 2026-09-30). */
    public static final double REACH = 64.0;

    /** How long a shelter with no way there, or too long a one, is left alone: a minute. */
    static final int NO_WAY_TICKS = 1_200;

    /** How long a shelter a threat stands in the way of is left alone: two seconds. */
    static final int BLOCKED_TICKS = 40;

    private final Pos in;
    private final Gait gait;
    private final boolean homeward;
    private final GoTo walk;
    private boolean judged;
    /** Whether it said it was pressing on in the dark: once a walk. */
    private boolean pressedOn;
    private String failure = "";

    public RunToShelter(int x, int y, int z) {
        this(x, y, z, Gait.SPRINT);
    }

    /** The same judged walk at another pace: a body going in for the night walks. */
    public RunToShelter(int x, int y, int z, Gait gait) {
        this(x, y, z, gait, false);
    }

    /** A walk in for the night, {@code homeward} weighed against the dark rather than {@link #REACH}. */
    public RunToShelter(int x, int y, int z, Gait gait, boolean homeward) {
        this.in = new Pos(x, y, z);
        this.gait = gait;
        this.homeward = homeward;
        this.walk = new GoTo(x, y, z, gait);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        TaskStatus status = walk.tick(ctx);
        if (status == TaskStatus.FAILED) {
            failure = walk.failureDetail();
            return status;
        }
        if (judged && status == TaskStatus.RUNNING && homeward && NightShelterStep.dark(ctx)) {
            return pressOn(ctx);
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
        ctx.journal().record(Category.BRAIN, "run for shelter", refusal.said());
        return TaskStatus.FAILED;
    }

    /** Why a route will not do, and how long to leave the shelter alone for it. */
    private record Refusal(String why, int aloneFor, String said) {
        Refusal(String why, int aloneFor) {
            this(why, aloneFor, "refused: " + why);
        }
    }

    /** At dark, the walk left weighed again: on if it is short enough, else a stop for a dig. */
    private TaskStatus pressOn(BrainContext ctx) {
        double left = left(ctx.actuators().mover().route(), ctx.percepts().position());
        if (left > NightShelterStep.pressOn()) {
            walk.cancel(ctx);
            ctx.knowledge().avoid(PoiKind.SHELTER, in, ctx.percepts().time() + NO_WAY_TICKS);
            failure = describe() + " given up — " + Math.round(left) + " blocks left at dark";
            ctx.journal().record(Category.BRAIN, "shelter", String.format(Locale.ROOT,
                    "too far to make it home, digging in: %.0f blocks left at dark", left));
            return TaskStatus.FAILED;
        }
        if (!pressedOn) {
            pressedOn = true;
            ctx.journal().record(Category.BRAIN, "shelter", String.format(Locale.ROOT,
                    "pressing on home in the dark, %.0f left", left));
        }
        return TaskStatus.RUNNING;
    }

    /**
     * How far the walk still is: the route from its waypoint nearest the body on, and in a straight
     * line from where it stops short to the door; the straight line alone while a leg is searched.
     */
    double left(@Nullable Path route, Pos here) {
        if (route == null || route.waypoints().isEmpty()) {
            return ShelterRace.distance(here, in);
        }
        List<Waypoint> ways = route.waypoints();
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < ways.size(); i++) {
            double distance = ShelterRace.distance(here, cell(ways.get(i)));
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        double left = best;
        for (int i = nearest + 1; i < ways.size(); i++) {
            left += ShelterRace.distance(cell(ways.get(i - 1)), cell(ways.get(i)));
        }
        Pos end = cell(ways.get(ways.size() - 1));
        return route.reachedGoal() ? left : left + ShelterRace.distance(end, in);
    }

    private static Pos cell(Waypoint way) {
        return new Pos(way.x(), way.y(), way.z());
    }

    /** Why this route will not do, or null when it will. */
    private @Nullable Refusal refusal(BrainContext ctx, Path route) {
        if (!route.reachedGoal() && !homeward) {
            return new Refusal("no way there without building", NO_WAY_TICKS);
        }
        Combatant me = ctx.percepts().selfAsCombatant().orElse(null);
        double pace = me == null ? 0.0 : me.pace();
        if (pace <= 0.0 && !homeward) {
            return null; // nothing to race with: the straight line already passed
        }
        List<ShelterRace.Runner> runners = pace > 0.0 ? ShelterRace.pressing(ctx, pace) : List.of();
        Pos at = ctx.percepts().position();
        double length = 0.0;
        double lead = Double.POSITIVE_INFINITY;
        for (Waypoint way : route.waypoints()) {
            Pos cell = cell(way);
            length += ShelterRace.distance(at, cell);
            at = cell;
            if (pace <= 0.0) {
                continue;
            }
            lead = ShelterRace.lead(runners, cell, length / pace);
            if (lead < 0.0) {
                return new Refusal(String.format(Locale.ROOT, "something gets to (%d, %d, %d) first",
                        cell.x(), cell.y(), cell.z()), BLOCKED_TICKS);
            }
        }
        if (!route.reachedGoal()) {
            length += ShelterRace.distance(at, in); // homeward only: the legs after this one
        }
        double reach = homeward ? NightShelterStep.reach(ctx) : REACH;
        if (length > reach) {
            String why = String.format(Locale.ROOT, "too far by the way there, %.0f blocks", length);
            return homeward ? new Refusal(why, NO_WAY_TICKS, String.format(Locale.ROOT,
                    "too far to make it home, digging in: %.0f blocks by the way there", length))
                    : new Refusal(why, NO_WAY_TICKS);
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

    public Gait gait() {
        return gait;
    }

    public boolean homeward() {
        return homeward;
    }

    public boolean pressedOn() {
        return pressedOn;
    }

    public boolean issued() {
        return walk.issued();
    }

    public boolean judged() {
        return judged;
    }

    /** Puts a saved run back where it had got to. */
    public RunToShelter resume(boolean issued, boolean judged) {
        return resume(issued, judged, false);
    }

    /** As above, with whether it already said it was pressing on in the dark. */
    public RunToShelter resume(boolean issued, boolean judged, boolean pressedOn) {
        walk.resume(issued);
        this.judged = judged;
        this.pressedOn = pressedOn;
        return this;
    }
}
