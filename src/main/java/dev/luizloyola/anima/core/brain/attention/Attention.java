package dev.luizloyola.anima.core.brain.attention;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * What a body looks at when nothing needs its eyes — the pure half of the gaze organ, and the one
 * place that answers "why is it staring at that".
 *
 * <p>Today one source: an <b>idle scan</b>, a bearing held for a few seconds and rolled again,
 * which is the floor of looking alive. Everything else arrives as another <em>source</em> scored
 * against it, so this is shaped as a picker with a single candidate.
 *
 * <p><b>The output is a world point, not a bearing.</b> It is what an eye aims at and what every
 * future source produces, and it makes the scan behave correctly for free: a body walking past the
 * spot it was idling beside turns its head to keep it, and drops it behind the shoulder. A bearing
 * would have made the head a compass needle glued to the body.
 *
 * <p><b>Not persisted, deliberately</b> — the justified exception to "anything outliving its tick
 * is saved": all of this state is re-rolled from live position within a couple of seconds, and no
 * behaviour, memory or plan reads it.
 *
 * <p><b>The randomness is not the brain's.</b> The organ hands in the body's entity random rather
 * than the saved {@code AgentRandom}: sharing that stream would let the number of head turns decide
 * which way the next wander rolled, and the brain's stream is saved and restored precisely so "the
 * roam it was going to pick is the roam it picks".
 */
public final class Attention {

    /**
     * How far to either side a scan would like to look — never further than the neck reaches
     * (see {@link #arc}). Idling is glancing about, not turning about: a body that swung its
     * shoulders round for no reason reads as agitated.
     */
    public static final int SCAN_ARC_DEGREES = 100;

    /**
     * How much of the scan is simply "forward again" — without it a scan never rests square and
     * the body reads as permanently distracted.
     */
    public static final double SCAN_FORWARD_CHANCE = 0.3;

    /** Steepest a scan tilts, up or down (degrees). Idle eyes are level; this is the wander. */
    public static final int SCAN_PITCH_DEGREES = 12;

    /**
     * How far out (blocks) a scanned point is placed: far enough that a step does not swing the
     * head, near enough that turning past it drops it rather than towing it.
     */
    public static final double SCAN_DISTANCE = 12.0;

    /**
     * Somewhere to look, for a while: a world point, when it stops being the answer, and why —
     * the {@code reason} is what a debug readout prints, so a stare can be told from a bug.
     */
    public record Focus(double x, double y, double z, long until, String reason) {

        public boolean live(long now) {
            return now < until;
        }
    }

    private Focus focus;

    /**
     * Where to look this tick, rolling a new focus whenever the last one has run out. Returns the
     * Same focus on every tick of its dwell: the easing and the turning belong to the organ, which
     * needs something to ease toward.
     *
     * @param eyeX where this body's eyes are, world X — the origin a scan is projected from
     * @param eyeY eye height, world Y
     * @param eyeZ eye position, world Z
     * @param bodyYawDegrees which way the body is squared up (Minecraft convention: 0° is +Z), the
     *     axis the scan arc is measured from — a scan is relative to the shoulders, never to the
     *     head, or each roll would compound the last and the body would slowly spin
     * @param now the game tick, the same clock the dwell is counted in
     * @param random this body's stream of chance — not the brain's (see the class note)
     * @param profile what this body is like; the dwell is a species' business
     */
    public Focus tick(double eyeX, double eyeY, double eyeZ, double bodyYawDegrees, long now,
            RandomGenerator random, AgentProfile profile) {
        if (this.focus != null && this.focus.live(now)) {
            return this.focus;
        }
        this.focus = scan(eyeX, eyeY, eyeZ, bodyYawDegrees, now, random, profile);
        return this.focus;
    }

    /** Whatever is currently being looked at, or {@code null} — for readouts, never a decision. */
    public Focus current() {
        return this.focus;
    }

    /**
     * How wide this body's idle scan actually is: {@link #SCAN_ARC_DEGREES} or as far as its neck
     * goes, whichever is less.
     *
     * <p>The clamp keeps idling from becoming pacing — a scan past the neck's limit is one the
     * shoulders must come round for, so a short-necked body would otherwise pivot every few
     * seconds.
     */
    public static int arc(AgentProfile profile) {
        return Math.min(SCAN_ARC_DEGREES, profile.i(ProfileAspect.GAZE_MAX_TWIST_DEGREES));
    }

    /**
     * Drop the current focus, so the next {@link #tick} rolls a fresh one. What the organ calls
     * when something outranked the idle look for a while: coming back to a scan that was chosen
     * before the interruption would aim at where the body used to be standing.
     */
    public void clear() {
        this.focus = null;
    }

    /** One roll of the idle scan: a bearing off the shoulders, a small tilt, a point out there. */
    private Focus scan(double eyeX, double eyeY, double eyeZ, double bodyYawDegrees, long now,
            RandomGenerator random, AgentProfile profile) {
        int arc = arc(profile);
        boolean forward = random.nextDouble() < SCAN_FORWARD_CHANCE;
        double offset = forward ? 0.0 : random.nextInt(2 * arc + 1) - arc;
        double pitch = random.nextInt(2 * SCAN_PITCH_DEGREES + 1) - SCAN_PITCH_DEGREES;
        double yaw = Math.toRadians(bodyYawDegrees + offset);
        double tilt = Math.toRadians(pitch);
        // Minecraft's convention, the same one the sense cones are measured in: yaw 0° faces +Z,
        // and a POSITIVE pitch looks down (BeingSensorCore#inCone).
        double horizontal = SCAN_DISTANCE * Math.cos(tilt);
        double x = eyeX - Math.sin(yaw) * horizontal;
        double z = eyeZ + Math.cos(yaw) * horizontal;
        double y = eyeY - SCAN_DISTANCE * Math.sin(tilt);
        int min = profile.i(ProfileAspect.GAZE_SCAN_MIN_TICKS);
        int max = profile.i(ProfileAspect.GAZE_SCAN_MAX_TICKS);
        // A species may declare the pair crossed over: bounds travel with each aspect, but nothing
        // can express "and above the other one". Taking the wider reading beats throwing inside a
        // tick.
        int dwell = max <= min ? Math.max(1, min) : min + random.nextInt(max - min);
        return new Focus(x, y, z, now + dwell,
                forward ? "scan ahead" : String.format(Locale.ROOT, "scan %+.0f°", offset));
    }
}
