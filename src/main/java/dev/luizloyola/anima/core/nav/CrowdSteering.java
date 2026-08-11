package dev.luizloyola.anima.core.nav;

import java.util.List;

/**
 * Local avoidance: the small turn that walks a body <em>around</em> the ones in front of it.
 *
 * <p>The plan stays blind to bodies on purpose — a route is searched off-thread over a block
 * snapshot and walked over seconds, so pricing entities in buys a detour around where somebody
 * <em>was</em>. This is a per-tick signed deflection added to the heading the follower decided.
 *
 * <p>It fixes the dead-on approach: bodies at the same lateral offset (agents spawn on whole
 * coordinates) collide centre-to-centre and vanilla's push resolves straight backwards — the
 * walker shoves the standee down the corridor instead of sliding past as any angle would. So the
 * deadband matters more than the maximum: a body precisely in the way must still pick a side.
 *
 * <p>Which side: away from wherever the neighbour leans, <em>right</em> when it leans nowhere —
 * stable across ticks, and two bodies both bearing right pass shoulder to shoulder where a
 * mirroring rule deadlocks.
 *
 * <p>Pure and frame-independent: blocks in, radians out, direction any non-zero vector. Right is
 * toward {@code (-dirZ, dirX)}, positive in Minecraft's clockwise yaw, so the caller adds the
 * result onto its heading.
 */
public final class CrowdSteering {
    private CrowdSteering() {}

    /** A body to steer around, as the horizontal disc it occupies. */
    public record Neighbour(double x, double z, double radius) {}

    /**
     * Surface-to-surface gap (blocks) at which a neighbour starts to matter — far enough out for
     * the swerve to build a sideways offset rather than become a shove. Also the box the caller
     * must gather over, plus a body's radius at each end.
     */
    public static final double REACH = 1.8;

    /**
     * Lateral room beyond the two radii before a neighbour counts as in the corridor at all.
     * Without it a body flinches its way down a street, steering around people it walks past.
     */
    private static final double CLEARANCE = 0.25;

    /**
     * How close to dead-on counts as dead-on. Inside it the lateral offset is noise around zero and
     * a body reading a side out of it flips sides with the noise, so keep-right takes over. Small:
     * outside the band the near side really is the right answer.
     */
    private static final double DEADBAND = 0.15;

    /**
     * The widest this will ever turn a body from its heading — a cap, not a target, reached only
     * when something is touching and directly ahead. Raising it buys a wider berth and costs path
     * fidelity: the follower's plane-advance stops claiming a waypoint once the body drifts more
     * than 0.6 blocks off the leg, and a turn this side of 45° keeps the drift inside that.
     */
    private static final double MAX_DEFLECTION = Math.toRadians(40.0);

    /**
     * How far to turn, in radians, to walk around {@code crowd} rather than into it: positive to
     * the body's right (toward {@code (-dirZ, dirX)}), negative to its left, zero when the way is
     * clear. Never more than {@link #MAX_DEFLECTION}.
     *
     * @param x       the body's position
     * @param z       the body's position
     * @param dirX    where it wants to go — any non-zero vector, need not be normalised
     * @param dirZ    where it wants to go
     * @param radius  the body's own half-width
     * @param crowd   everything nearby worth not walking into; may be empty
     */
    public static double deflection(double x, double z, double dirX, double dirZ,
                                    double radius, List<Neighbour> crowd) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (crowd.isEmpty() || len < 1.0e-6) {
            return 0.0;
        }
        double aheadX = dirX / len;
        double aheadZ = dirZ / len;
        double rightX = -aheadZ;
        double rightZ = aheadX;

        // Demands accumulate signed, so neighbours on opposite sides cancel and the body threads
        // the gap — and squeezes through when there is none, which beats shoving one of them aside.
        double demand = 0.0;
        for (Neighbour neighbour : crowd) {
            double toX = neighbour.x() - x;
            double toZ = neighbour.z() - z;
            double distance = Math.sqrt(toX * toX + toZ * toZ);
            if (distance < 1.0e-4) {
                demand += 1.0; // standing inside each other: any way out will do, take the rule's
                continue;
            }
            double ahead = toX * aheadX + toZ * aheadZ;
            if (ahead <= 0.0) {
                continue; // behind us — walking on is already walking away
            }
            double span = radius + neighbour.radius();
            double side = toX * rightX + toZ * rightZ;
            if (Math.abs(side) > span + CLEARANCE) {
                continue; // outside the corridor: we miss them without trying
            }
            double gap = distance - span;
            if (gap >= REACH) {
                continue;
            }
            // Two falloffs: the gap says how urgent this is, the bearing (ahead/distance, the
            // cosine) how much it is in the way at all. Without the second, two agents walking a
            // corridor side by side shy away from each other the whole walk.
            double urgency = gap <= 0.0 ? 1.0 : 1.0 - gap / REACH;
            demand += (side > DEADBAND ? -urgency : urgency) * (ahead / distance);
        }
        return Math.max(-1.0, Math.min(1.0, demand)) * MAX_DEFLECTION;
    }
}
