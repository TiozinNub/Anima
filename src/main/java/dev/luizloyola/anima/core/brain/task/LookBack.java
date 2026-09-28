package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The look back that ends each leg of a flight (combat spec, decision 16): stop, turn to where the
 * threats out of sight were last known, and see whether they are there.
 *
 * <p><b>Only when they are far, or quiet</b> (Luiz). Stopping lets a chaser close, so there is no
 * look while any threat out of sight is inside the ear's reach and was heard within the last second:
 * its steps already place it. Nor while a lit fuse is in reach. What is left is mostly a threat 12
 * to 16 blocks back, out of earshot and pressing from a spot that falls further behind every leg,
 * and anything that flies or makes no sound.
 *
 * <p><b>The sense does the confirming.</b> A head that turns re-checks at once: a threat seen is
 * refreshed, and one whose empty spot is in plain view loses its place and stops pressing. If
 * something looked for was not there and nothing else presses, the body stops fleeing and looks
 * around for it, a third of a turn either way. A sound or a sight of it ends the search, and fight
 * or flight answers afresh.
 *
 * <p>Nothing is saved: a look is under a second, and one a reload restarts costs nothing.
 */
public final class LookBack implements PrimitiveTask {

    /** How far out a search look is placed, so the body's own step does not swing the head. */
    static final double SEARCH_DISTANCE = 12.0;

    /** A third of a turn: three looks of a Person's 150° cone cover the circle. */
    private static final double SEARCH_STEP = 2.0 * Math.PI / 3.0;

    /** How far past a lit fuse's blast reach is still no place to stop — fight or flight's margin. */
    private static final double FUSE_MARGIN = 2.0;

    /** Floor for the {@code 1/distance²} weighting, as {@code FleeStep}'s. */
    private static final double MIN_WEIGHT_DISTANCE = 0.1;

    /** Where to look, in order: the look back first, then the search's. World points. */
    private final List<double[]> points = new ArrayList<>();
    /** What the look back was for, and what the body called each when it looked. */
    private final Map<BeingId, String> sought = new LinkedHashMap<>();
    private boolean started;
    private boolean searching;
    private int index;
    private int remaining;

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (!started) {
            started = true;
            if (!begin(ctx)) {
                return TaskStatus.SUCCESS;
            }
        } else if (searching) {
            Being found = placedAgain(ctx);
            if (found != null) {
                say(ctx, "found " + found.knownAs());
                return TaskStatus.SUCCESS;
            }
        }
        if (remaining == 0) {
            if (!searching) {
                if (!searchAfter(ctx)) {
                    return TaskStatus.SUCCESS;
                }
            } else if (++index >= points.size()) {
                say(ctx, "no sign of " + String.join(", ", sought.values()));
                return TaskStatus.SUCCESS;
            }
            remaining = ctx.profile().i(ProfileAspect.FLEE_LOOK_TICKS);
        }
        remaining--;
        double[] at = points.get(index);
        // WORK, so the legs' look along the heading cannot outrank it, and a snap: a look back that
        // eased round would be half over before the eyes arrived.
        ctx.actuators().gazer().lookAt(at[0], at[1], at[2], Gazer.Priority.WORK, 1, true);
        return TaskStatus.RUNNING;
    }

    /**
     * Whether to look back at all, and where: the nearness-weighted middle of the threats out of
     * sight, at their eye height, so a flyer is looked for up where it was.
     */
    private boolean begin(BrainContext ctx) {
        AgentProfile profile = ctx.profile();
        int ticks = profile.i(ProfileAspect.FLEE_LOOK_TICKS);
        if (ticks <= 0 || profile.i(ProfileAspect.SENSES_CONE_DEGREES) >= 360) {
            return false; // never looks back, or never needs to
        }
        int ear = profile.i(ProfileAspect.SENSES_HEARING_RADIUS);
        double weightSum = 0.0;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (Being being : ctx.percepts().beings()) {
            if (!being.aggressive() || being.herd()) {
                continue;
            }
            Optional<Combatant> body = ctx.percepts().combatant(being.id());
            if (body.isPresent() && body.get().fuse() > 0.0
                    && being.distance() < body.get().blastReach() + FUSE_MARGIN) {
                return false; // no time to stop
            }
            // No cut-off at sight range: a spot remembered 28 blocks back is where a chaser 15 blocks
            // back is found, on the same bearing — the flight that ended on such a spot is the bug.
            if (being.awareness() == Being.Awareness.SEEN) {
                continue;
            }
            boolean far = being.distance() > ear;
            boolean quiet = being.awareness() == Being.Awareness.REMEMBERED;
            if (!far && !quiet) {
                return false; // close, and its steps place it: stopping only lets it close
            }
            double distance = Math.max(being.distance(), MIN_WEIGHT_DISTANCE);
            double weight = 1.0 / (distance * distance);
            weightSum += weight;
            x += weight * (being.pos().x() + 0.5);
            y += weight * (being.pos().y() + being.eyeHeight());
            z += weight * (being.pos().z() + 0.5);
            sought.put(being.id(), being.knownAs());
        }
        if (sought.isEmpty()) {
            return false;
        }
        points.add(new double[] {x / weightSum, y / weightSum, z / weightSum});
        remaining = ticks;
        return true;
    }

    /**
     * What the look back found, said; and whether to search: something it was for is not there,
     * and nothing else presses.
     */
    private boolean searchAfter(BrainContext ctx) {
        List<String> said = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<BeingId, String> entry : sought.entrySet()) {
            Being being = perceived(ctx, entry.getKey());
            if (being == null) {
                missing.add(entry.getValue());
                said.add(entry.getValue() + " is not where it was");
            } else if (being.awareness() == Being.Awareness.SEEN) {
                said.add(String.format(Locale.ROOT, "%s is %.0f blocks away",
                        being.knownAs(), being.distance()));
            } else {
                // Its spot is out of sight range or behind something: neither confirmed nor refuted.
                said.add(entry.getValue() + " is out of view");
            }
        }
        say(ctx, "looked back: " + String.join("; ", said));
        if (missing.isEmpty() || pressing(ctx)) {
            return false;
        }
        say(ctx, "looking around");
        Pos here = ctx.percepts().position();
        double eyeX = here.x() + 0.5;
        double eyeY = here.y() + ctx.percepts().eyeHeight();
        double eyeZ = here.z() + 0.5;
        double[] back = points.get(0);
        double bearing = Math.atan2(back[2] - eyeZ, back[0] - eyeX);
        for (double turn : new double[] {SEARCH_STEP, -SEARCH_STEP}) {
            points.add(new double[] {eyeX + Math.cos(bearing + turn) * SEARCH_DISTANCE, eyeY,
                    eyeZ + Math.sin(bearing + turn) * SEARCH_DISTANCE});
        }
        searching = true;
        index = 1;
        return true;
    }

    /** Something sought, back in the percepts: a sound or a sight has placed it again. */
    private @Nullable Being placedAgain(BrainContext ctx) {
        for (BeingId id : sought.keySet()) {
            Being being = perceived(ctx, id);
            if (being != null) {
                return being;
            }
        }
        return null;
    }

    /**
     * Whether any threat still in the percepts is inside its reach — fight or flight's rule without
     * its ramp: a shooter's reach is as far as this body perceives, anything else's its flee range.
     * Then there is still something to run from, and no time to search.
     */
    private static boolean pressing(BrainContext ctx) {
        AgentProfile profile = ctx.profile();
        for (Being being : ctx.percepts().beings()) {
            if (!being.aggressive()) {
                continue;
            }
            boolean ranged = being.gear().ranged() || being.activity() == Being.Activity.AIMING
                    || ctx.danger().ranged(being.species());
            double reach = ranged
                    ? profile.i(ProfileAspect.SENSES_RADIUS)
                    : profile.d(ProfileAspect.FLEE_RANGE);
            if (being.distance() < reach) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Being perceived(BrainContext ctx, BeingId id) {
        for (Being being : ctx.percepts().beings()) {
            if (being.id().equals(id)) {
                return being;
            }
        }
        return null;
    }

    private static void say(BrainContext ctx, String line) {
        ctx.journal().record(Category.BRAIN, "look back", line);
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing to release: a gaze claim expires on its own.
    }

    @Override
    public String describe() {
        return searching ? "look around" : "look back";
    }
}
