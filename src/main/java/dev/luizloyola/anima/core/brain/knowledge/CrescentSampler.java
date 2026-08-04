package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * The "notice as you go" trigger geometry: emits the columns that <em>newly came into
 * view</em> — the leading crescent. Event-driven, so an idling body costs zero.
 *
 * <p><b>A cone with a halo, not a disc</b>: omnidirectional out to {@link #nearRadius}, then
 * {@link #coneDegrees} either side of the head's bearing. A 150° cone samples 42% of its disc, so
 * the same read budget reaches half again as far.
 *
 * <p><b>Turning is moving.</b> The difference is taken against the last view actually enumerated —
 * both its origin and its bearing — so a body drifting a degree per step never opens a gap. Moving
 * one block sweeps ≈ 2R columns; turning one degree ≈ π(R² − r₀²)/360 of wedge; a discontinuity
 * (first sighting, teleport, any jump beyond R) yields the whole view.
 *
 * <p>Deliberately <em>not</em> gated on pitch, unlike the being sense: a column's elevation is
 * known only after its heightmap read, and pitch swings far more than bearing, so it would
 * re-enumerate the ground at every glance at the sky.
 */
public final class CrescentSampler {

    /**
     * How far the head must turn, standing still, before the view is enumerated again. Sized to the
     * smallest thing worth noticing at the rim (a 5-wide canopy at 24 blocks subtends ~12°), so a
     * turn under this cannot have brought a whole tree into view. Costs at most one wedge this wide
     * of latency on a slow, deliberate turn.
     */
    public static final double YAW_HYSTERESIS_DEGREES = 10.0;

    /** Horizontal sense radius in blocks — how far this body notices places, where it looks. */
    public static int radius(AgentProfile profile) {
        return profile.i(ProfileAspect.PLACES_RADIUS);
    }

    /**
     * The omnidirectional inner halo, clamped to {@link #radius}: a body declaring a halo wider
     * than its reach is omnidirectional, not broken.
     */
    public static int nearRadius(AgentProfile profile) {
        return Math.min(profile.i(ProfileAspect.PLACES_NEAR_RADIUS), radius(profile));
    }

    /** Full horizontal aperture in degrees; 360 restores the old disc exactly. */
    public static int coneDegrees(AgentProfile profile) {
        return profile.i(ProfileAspect.PLACES_CONE_DEGREES);
    }

    private final AgentProfile profile;
    /** Origin of the last view enumerated — null until the first one. */
    private Column center;
    /** Bearing of that same view. Meaningless while {@link #center} is null. */
    private double facing;

    public CrescentSampler(AgentProfile profile) {
        this.profile = profile;
    }

    /**
     * Advances to the body's current feet cell and bearing and returns the newly-in-view columns
     * — empty when neither moved enough to reveal anything.
     *
     * @param yawDegrees head bearing, Minecraft convention (0° = +Z), not body rotation
     */
    public List<Column> advance(Pos feet, double yawDegrees) {
        Column now = new Column(feet.x(), feet.z());
        if (now.equals(this.center)
                && Math.abs(angleDelta(yawDegrees, this.facing)) < YAW_HYSTERESIS_DEGREES) {
            return List.of();
        }
        Column before = this.center;
        double facingBefore = this.facing;
        this.center = now;
        this.facing = yawDegrees;
        // Read the geometry once per sweep: a reload between sweeps changes it cleanly, but a
        // single crescent is always computed against one consistent view.
        int radius = radius(this.profile);
        long radiusSq = (long) radius * radius;
        long nearSq = (long) nearRadius(this.profile) * nearRadius(this.profile);
        double cosHalf = Math.cos(Math.toRadians(coneDegrees(this.profile) / 2.0));
        // The two bearings are constants of this sweep, and the cell loop below runs (2R+1)²
        // times — 2,401 at a Person's reach, several times a second, per agent. Taking sine and
        // cosine of the same angle inside it measured 9.5 us of every sensor tick, against 4.9
        // for the whole near-field probe loop it feeds.
        double yaw = Math.toRadians(yawDegrees);
        double sinYaw = -Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinBefore = -Math.sin(Math.toRadians(facingBefore));
        double cosBefore = Math.cos(Math.toRadians(facingBefore));
        List<Column> fresh = new ArrayList<>();
        boolean jump = before == null || horizontalDistSq(before, now) > radiusSq;
        int originX = before == null ? 0 : before.x();
        int originZ = before == null ? 0 : before.z();
        for (int dx = -radius; dx <= radius; dx++) {
            long dxSq = (long) dx * dx;
            for (int dz = -radius; dz <= radius; dz++) {
                long distSq = dxSq + (long) dz * dz;
                if (distSq > radiusSq) {
                    continue;
                }
                if (distSq > nearSq && !inCone(dx, dz, distSq, sinYaw, cosYaw, cosHalf)) {
                    continue;
                }
                int x = now.x() + dx;
                int z = now.z() + dz;
                // Tested on the coordinates rather than on a Column: this is the hot path, and
                // allocating one to ask would be an allocation per cell of the whole disc.
                if (jump || !inView(originX, originZ, sinBefore, cosBefore, x, z,
                        nearSq, radiusSq, cosHalf)) {
                    fresh.add(new Column(x, z));
                }
            }
        }
        return fresh;
    }

    /** Whether the cell stood in the view centred on that origin at that bearing. */
    private static boolean inView(int originX, int originZ, double sinYaw, double cosYaw,
            int x, int z, long nearSq, long radiusSq, double cosHalf) {
        long dx = (long) x - originX;
        long dz = (long) z - originZ;
        long distSq = dx * dx + dz * dz;
        if (distSq > radiusSq) {
            return false;
        }
        return distSq <= nearSq || inCone(dx, dz, distSq, sinYaw, cosYaw, cosHalf);
    }

    /**
     * Bearing test, shared with the being sense's convention (yaw 0° = +Z). Never called at
     * {@code distSq == 0}: the origin column is inside any halo.
     *
     * <p>Compares squares instead of dividing by a root — no transcendental, no root per cell.
     * {@code dot} is the projection times the distance, so {@code dot/√distSq ≥ cosHalf} becomes
     * {@code dot²} against {@code cosHalf²·distSq}, sign handled explicitly because squaring loses
     * it. A cone wider than a half-turn has a negative cosine and excludes only what is behind —
     * the second arm.
     */
    private static boolean inCone(long dx, long dz, long distSq, double sinYaw, double cosYaw,
            double cosHalf) {
        double dot = sinYaw * dx + cosYaw * dz;
        if (cosHalf <= 0.0) {
            return dot >= 0.0 || dot * dot <= cosHalf * cosHalf * distSq;
        }
        return dot >= 0.0 && dot * dot >= cosHalf * cosHalf * distSq;
    }

    /** Signed shortest turn from {@code b} to {@code a}, in (−180, 180]. */
    private static double angleDelta(double a, double b) {
        double delta = (a - b) % 360.0;
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return delta;
    }

    private static long horizontalDistSq(Column a, Column b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
