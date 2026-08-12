package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.Needs;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;

/**
 * How much a body would rather not be standing somewhere — the price of a spot, for the one
 * decision that has no errand behind it. The wander's fear term generalised, not a new system:
 * keep the cheapest of a few rolls.
 *
 * <p><b>A cost, not a score.</b> Lower is better, because the term it grew from was a danger
 * reading and inverting it would make the one number everybody understands read backwards.
 *
 * <p><b>Crowding is not optional.</b> A comfort landscape has a minimum, and every body in it
 * walks toward the same cell; without the crowding term the reward is a settlement converged
 * into one square metre — worse than the scattering it was meant to fix, and it would read as a
 * pathfinder bug rather than a missing term here.
 */
public final class Comfort {

    /**
     * How close another body may be before a spot is worth avoiding, in blocks. Two: close enough
     * to stand and talk, far enough not to be inside each other.
     */
    public static final int PERSONAL_SPACE = 2;

    /** What standing on top of somebody costs — well above the danger of an ordinary quiet day. */
    public static final double CROWDING = 0.6;

    /** How much of a say wanting company (or wanting none) gets. Below fear, above nothing. */
    public static final double COMPANY_PULL = 0.35;

    /** Beyond this (blocks) nobody is company, they are scenery. */
    public static final double COMPANY_REACH = 12.0;

    private Comfort() {
    }

    /**
     * What it would cost this body to be at {@code candidate} — fear, elbow room, and whether it
     * currently wants people nearer or further away.
     *
     * @param candidate the cell being priced
     * @param beings everything this body currently perceives; crowding counts every body, company
     *     counts only the ones it could be sociable with
     * @param field what it is afraid of and where — already the wander's term, unchanged
     * @param needs its gauges, read for the company side only; a species with no company need
     *     has no opinion, which the ramp answers as neutral
     * @param profile what this body is like — the levels of a need are declared per species
     */
    public static double cost(Pos candidate, List<Being> beings, DangerField field, Needs needs,
            AgentProfile profile) {
        double cost = field.isEmpty() ? 0.0 : field.at(candidate);
        double nearest = Double.MAX_VALUE;
        for (Being being : beings) {
            double distance = distance(candidate, being.pos());
            if (distance <= PERSONAL_SPACE) {
                // Every body counts here, not only the sociable ones.
                cost += CROWDING;
            }
            if (being.kind().minded() && distance < nearest) {
                nearest = distance;
            }
        }
        int side = companySide(needs, profile);
        if (side != 0 && nearest < Double.MAX_VALUE) {
            double reach = Math.min(1.0, nearest / COMPANY_REACH);
            // Below the band, distance from people is the cost; above it, closeness is.
            cost += COMPANY_PULL * (side < 0 ? reach : 1.0 - reach);
        }
        return cost;
    }

    /**
     * Whether anything here would price two spots differently. A body alone in a quiet place has
     * nothing to weigh and should not pay for four rolls to discover it.
     */
    public static boolean worthWeighing(List<Being> beings, DangerField field) {
        return !field.isEmpty() || !beings.isEmpty();
    }

    /**
     * Whether somebody is standing too close right now — a reason to move that does not wait for
     * the dice, since a wander roll would leave them inside a neighbour for most of a minute.
     */
    public static boolean crowded(Pos here, List<Being> beings) {
        for (Being being : beings) {
            if (distance(here, being.pos()) <= PERSONAL_SPACE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which way this body's company need is pulling: {@code -1} wants people, {@code +1} wants to
     * be left alone, {@code 0} has no opinion (content — or a species with no such need at all).
     */
    private static int companySide(Needs needs, AgentProfile profile) {
        if (!needs.has(NeedKind.COMPANY) || NeedKind.COMPANY.ramp() == null) {
            return 0;
        }
        return NeedKind.COMPANY.ramp().side(profile, needs.value(NeedKind.COMPANY));
    }

    /** Horizontal distance in blocks — height has no say in who is crowding whom. */
    private static double distance(Pos from, Pos to) {
        double dx = from.x() - to.x();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
