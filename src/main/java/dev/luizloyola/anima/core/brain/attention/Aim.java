package dev.luizloyola.anima.core.brain.attention;

/**
 * Where a head ends up this tick, given where it is and what it is trying to look at — the gaze
 * organ's whole geometry, pure so a test can argue with it. Turn, tilt and whether the shoulders
 * come along are answered together because splitting them would decide the twist limit twice.
 *
 * <p><b>Angles are Minecraft's</b>, the convention the sense cones are measured in: yaw 0° faces +Z
 * and grows clockwise, and a POSITIVE pitch looks DOWN.
 */
public record Aim(float headYaw, float pitch, float bodyYaw, boolean turning, boolean reachable) {

    /**
     * Steepest a head will tilt. Short of the ±90 vanilla permits, which renders as a snapped neck
     * rather than as looking up.
     */
    public static final float MAX_PITCH = 80.0F;

    /**
     * How fast the shoulders come round, as a fraction of the head's own turn. At the same rate the
     * head never leads and the body pivots as one rigid piece, which reads as being pointed at
     * something rather than looking at it.
     */
    public static final float BODY_FOLLOW = 0.5F;

    /** How close to square ends a turn. The ease lands exactly, so this only absorbs float dust. */
    private static final float SQUARE_ENOUGH = 1.0e-3F;

    /**
     * Horizontal distance below which a target has no bearing worth turning to — underfoot or
     * overhead, where the yaw is sub-block jitter and a pillaring builder would spin on the spot
     * chasing it. The tilt still carries the whole look.
     */
    public static final double NO_BEARING = 0.05;

    /**
     * Work out this tick's head, from the offset to whatever is being looked at.
     *
     * @param dx offset from the EYE to the target, world X
     * @param dy offset from the eye, world Y — the sign that decides up or down
     * @param dz offset from the eye, world Z
     * @param headYaw where the head points now
     * @param bodyYaw where the shoulders are squared now
     * @param pitch where the head is tilted now
     * @param step the most this body's head turns in one tick (its reflexes)
     * @param maxTwist how far the head goes from the shoulders before they must come along
     * @param bodyFree whether the shoulders are ours to turn — false while the legs are driving,
     *     since the same yaw steers the body and stealing it would walk the body off its path
     * @param turning whether the shoulders were ALREADY coming round, from this method's own
     *     previous answer. A latch rather than a fresh decision each tick, and it has to be: a
     *     body that turned only while the neck was over its limit would stop the instant the neck
     *     was comfortable and stand there wrenched most of the way round, which is the exact
     *     posture this organ exists to abolish. Once the shoulders start, they finish
     * @return the new angles, whether the shoulders are still coming round, and whether this is a
     *     thing the body can actually look at
     */
    public static Aim of(double dx, double dy, double dz, float headYaw, float bodyYaw, float pitch,
            float step, float maxTwist, boolean bodyFree, boolean turning) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantPitch = clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)),
                -MAX_PITCH, MAX_PITCH);
        float newPitch = ease(pitch, wantPitch, step);
        if (horizontal < NO_BEARING) {
            // Straight up or straight down: the tilt above is the whole of the look, and the yaw
            // stays exactly where it was rather than chasing a bearing made of rounding error.
            return new Aim(headYaw, newPitch, bodyYaw, false, true);
        }
        float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float twist = wrap(wantYaw - bodyYaw);
        // The legs own the yaw while they are driving, so a walking body's shoulders never come
        // round for a look — however far round the look is.
        boolean comesRound = bodyFree && (turning || Math.abs(twist) > maxTwist);
        float newBody = comesRound ? ease(bodyYaw, wantYaw, step * BODY_FOLLOW) : bodyYaw;
        // Whatever the shoulders are doing, the neck has a limit and the head stops at it. That is
        // also the walking case: aim as far round as it goes and report reachable=false, so the
        // body drops the target instead of staring over its own shoulder.
        float headTwist = wrap(wantYaw - newBody);
        float headTarget = Math.abs(headTwist) > maxTwist
                ? newBody + Math.signum(headTwist) * maxTwist
                : wantYaw;
        // The latch drops the moment the shoulders are square and not before, so the next tick
        // treats an ordinary glance as a glance rather than another pivot.
        boolean squared = Math.abs(wrap(wantYaw - newBody)) <= SQUARE_ENOUGH;
        return new Aim(ease(headYaw, headTarget, step), newPitch, newBody, comesRound && !squared,
                comesRound || Math.abs(twist) <= maxTwist);
    }

    /** Turn {@code from} toward {@code to} by at most {@code step} degrees, the short way round. */
    public static float ease(float from, float to, float step) {
        return from + clamp(wrap(to - from), -step, step);
    }

    /** {@code degrees} folded into [-180, 180) — the difference between two bearings, signed. */
    public static float wrap(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.min(max, Math.max(min, value));
    }
}
