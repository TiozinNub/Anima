package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.instinct.FightOrFlightInstinct;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Sides;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The race to a door (shelter spec, rungs 4 and 5): who else is running for it, and by how much
 * this body gets there first. Every threat is given its straight line at its own pace, the most
 * generous a threat can be given.
 */
final class ShelterRace {

    /** How much sooner than any threat a body must get to a door to count on it: 1 s. */
    static final int DOOR_MARGIN_TICKS = 20;

    /** One threat pressing on the body, and how fast it moves, in blocks a tick. */
    record Runner(Being being, double pace) {
    }

    private ShelterRace() {
    }

    /**
     * Every being pressing on this body, with its pace. One with nothing to size up is taken for
     * as quick as this body, which is generous to it.
     */
    static List<Runner> pressing(BrainContext ctx, double myPace) {
        List<Runner> runners = new ArrayList<>();
        for (Being being : ctx.percepts().beings()) {
            Combatant them = ctx.percepts().combatant(being.id()).orElse(null);
            if (FightOrFlightInstinct.pressureOf(ctx.profile(), ctx.danger(), being,
                    ctx.percepts().attackedLately(being.id()), them) > 0.0) {
                runners.add(new Runner(being, them == null ? myPace : them.pace()));
            }
        }
        return runners;
    }

    /**
     * The runners a shelter would shut out, or null when one of them it would not: already
     * inside, a lit fuse (a door does not stop a blast), or something shut doors do not stop.
     * Empty when nothing presses, which is no reason to shelter at all.
     */
    static @Nullable List<Runner> shutOutBy(BrainContext ctx, double myPace, Enclosure.Holes holes,
                                            Predicate<Pos> inside) {
        List<Runner> runners = pressing(ctx, myPace);
        for (Runner runner : runners) {
            Combatant them = ctx.percepts().combatant(runner.being().id()).orElse(null);
            if (inside.test(runner.being().pos()) || them != null && them.fuse() > 0.0
                    || !Sides.keptOut(Enclosure.Openness.OPENABLE, holes, them)) {
                return null;
            }
        }
        return runners;
    }

    /** Ticks by which this body, there after {@code mine} ticks, beats every runner to {@code cell}. */
    static double lead(List<Runner> runners, Pos cell, double mine) {
        double lead = Double.POSITIVE_INFINITY;
        for (Runner runner : runners) {
            double theirs = runner.pace() <= 0.0 ? Double.POSITIVE_INFINITY
                    : distance(runner.being().pos(), cell) / runner.pace();
            lead = Math.min(lead, theirs - mine);
        }
        return lead;
    }

    static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
