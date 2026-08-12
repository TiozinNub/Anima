package dev.luizloyola.anima.core.brain.attention;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * What a body looks at — the pure half of the gaze organ. Every {@link Salience.Source} offers
 * priced {@link Salience.Candidate}s; this class picks one, holds it long enough to be a look, and
 * remembers recent looks so a body attends rather than stares.
 *
 * <p>Below {@link #SCAN_SCORE} the body idly looks around instead — a competitor, not a fallback
 * branch. Scoring runs every {@link #DECIDE_INTERVAL} ticks and tracking
 * ({@link Salience.Source#track}) every tick, so the expensive half is paid at a tenth of the rate.
 * The output is a world point, never a bearing.
 *
 * <p>Not persisted — the justified exception to the rule that anything outliving its
 * tick is saved: the state re-derives from live perception within a second or two and nothing reads
 * it. The randomness is the caller's generator, not the brain's saved {@code AgentRandom}; sharing
 * it would let head turns decide the next wander roll.
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
     * What idly looking around is worth. Every source is priced against this; below it, the body
     * would rather just look about.
     */
    public static final double SCAN_SCORE = 0.12;

    /** The scan's key. One key for all of them: they are the same act, not a series of things. */
    public static final String SCAN_KEY = "scan";

    /** How often the full scoring pass runs. Twice a second is faster than anyone changes their mind. */
    public static final int DECIDE_INTERVAL = 10;

    /**
     * How long after looking away something is worth a full look again — thirty seconds, ramping.
     * This is what stops a stare: otherwise the best thing in sight wins every decision for as
     * long as it is there.
     */
    public static final int REFRACTORY_TICKS = 600;

    /**
     * How much better a rival must be to take the eyes off what they are on — a quarter again. The
     * arbiter's stickiness, for the same reason: near-equal candidates should not flick the head.
     */
    public static final double PREEMPT = 0.25;

    /** How many keys the novelty memory holds before the stalest are dropped. */
    private static final int MEMORY_LIMIT = 64;

    /** How many of the last decision's candidates are kept for the readout. */
    private static final int OFFERS_KEPT = 3;

    private static final List<Salience.Source> SOURCES = new ArrayList<>();

    static {
        // Anima's own, in the order it would like them asked. Both are mechanism: neither knows
        // what a Person is.
        register(new BeingSource());
        register(new ThingSource());
    }

    /**
     * Teach every body in the world one more thing worth looking at — the extension point of this
     * feature: a consumer's own vocabulary is priced here and competes with everything else rather
     * than becoming a branch in a picker. Canonical per instance; call it at mod init.
     */
    public static synchronized void register(Salience.Source source) {
        for (Salience.Source existing : SOURCES) {
            if (existing.name().equals(source.name())) {
                throw new IllegalStateException("attention source \"" + source.name()
                        + "\" is already registered — two mods disagree about what it means");
            }
        }
        SOURCES.add(source);
    }

    /** Every registered source, in registration order. */
    public static synchronized List<Salience.Source> sources() {
        return List.copyOf(SOURCES);
    }

    /**
     * Somewhere to look, for a while. {@code reason} is what a debug readout prints, so a stare can
     * be told from a bug.
     *
     * @param snap whether the head should whip round rather than turn. A startle, and nothing else
     */
    public record Focus(String key, double x, double y, double z, long until, double score,
                        boolean snap, String reason) {

        public boolean live(long now) {
            return now < until;
        }

        /** The same look, at where the thing has moved to — see {@link Salience.Source#track}. */
        public Focus movedTo(Salience.Candidate candidate) {
            return new Focus(key, candidate.x(), candidate.y(), candidate.z(), until,
                    candidate.score(), snap, candidate.reason());
        }
    }

    private Focus focus;
    private long decidedAt = Long.MIN_VALUE;

    /**
     * What the last decision was between — the best few things on offer and what they were worth.
     * Kept because a body scanning past somebody in front of it reads identically whether the
     * picker never saw them or priced them at nothing; carried permanently, formatted only when
     * asked.
     */
    private final List<Salience.Candidate> offers = new ArrayList<>(OFFERS_KEPT);

    /** When each key was last looked away from — the whole of what makes a look wear off. */
    private final Map<String, Long> lastLookedAt = new HashMap<>();

    /**
     * Where to look this tick. Returns the same focus every tick of its dwell, its point kept
     * current if the thing moves.
     *
     * @param eyeX where this body's eyes are, world X — the origin every candidate is scored from
     * @param eyeY eye height, world Y
     * @param eyeZ eye position, world Z
     * @param bodyYawDegrees which way the body is squared up (Minecraft convention: 0° is +Z), the
     *     axis a scan's arc is measured from — off the shoulders, never off the head, or each roll
     *     would compound the last and the body would slowly spin
     * @param now the game tick
     * @param percepts what this body perceives; {@code knowledge} what it remembers;
     *     {@code danger} what frightens it
     * @param profile what this body is like: its reach, its neck, how long it rests its eyes
     * @param random this body's stream of chance — not the brain's (see the class note)
     */
    public Focus tick(double eyeX, double eyeY, double eyeZ, double bodyYawDegrees, long now,
            Percepts percepts, AgentKnowledge knowledge, DangerTable danger, AgentProfile profile,
            RandomGenerator random) {
        Salience.Scene scene = new Salience.Scene(eyeX, eyeY, eyeZ, bodyYawDegrees, now, percepts,
                knowledge, danger, profile, Collections.unmodifiableMap(this.lastLookedAt));
        keepUp(scene, now);
        if (this.focus == null || now - this.decidedAt >= DECIDE_INTERVAL) {
            this.decidedAt = now;
            decide(scene, now, random);
        }
        return this.focus;
    }

    /** Whatever is currently being looked at, or {@code null} — for readouts, never a decision. */
    public Focus current() {
        return this.focus;
    }

    /** What the last decision was between — see {@link #offers}. Readouts only. */
    public String verdict() {
        if (this.offers.isEmpty()) {
            return "nothing offered";
        }
        StringBuilder out = new StringBuilder();
        for (Salience.Candidate candidate : this.offers) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(String.format(Locale.ROOT, "%s %.2f", candidate.reason(),
                    candidate.score()));
        }
        return out.toString();
    }

    /**
     * Keeps this candidate if it is among the best few offered — an insertion sort over three
     * entries, cheaper than sorting everything a busy scene proposes.
     */
    private void remember(Salience.Candidate candidate) {
        int at = 0;
        while (at < this.offers.size() && this.offers.get(at).score() >= candidate.score()) {
            at++;
        }
        if (at >= OFFERS_KEPT) {
            return;
        }
        this.offers.add(at, candidate);
        while (this.offers.size() > OFFERS_KEPT) {
            this.offers.remove(this.offers.size() - 1);
        }
    }

    /**
     * Drop the current look so the next tick chooses afresh — a look chosen before an interruption
     * aims at where the body used to be standing.
     */
    public void clear() {
        this.focus = null;
    }

    /**
     * Keep the eyes on what they are on: follow it if it moved, drop it if it is gone or its dwell
     * has run out. Every tick, and cheap — this is the half that has to be smooth.
     */
    private void keepUp(Salience.Scene scene, long now) {
        if (this.focus == null) {
            return;
        }
        if (!SCAN_KEY.equals(this.focus.key())) {
            for (Salience.Source source : sources()) {
                if (!source.owns(this.focus.key())) {
                    continue;
                }
                Optional<Salience.Candidate> where = source.track(scene, this.focus.key());
                if (where.isPresent()) {
                    this.focus = this.focus.movedTo(where.get());
                } else {
                    // Gone: out of perception, dead, picked up. Looking at where it was would be a
                    // thousand-yard stare.
                    forget(now);
                }
                break;
            }
        }
        if (this.focus != null && !this.focus.live(now)) {
            forget(now);
        }
    }

    /** One full scoring pass: the best candidate, against what the eyes are already on. */
    private void decide(Salience.Scene scene, long now, RandomGenerator random) {
        Salience.Candidate best = null;
        this.offers.clear();
        for (Salience.Source source : sources()) {
            for (Salience.Candidate candidate : source.propose(scene)) {
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
                remember(candidate);
            }
        }
        if (best == null || best.score() < SCAN_SCORE) {
            // Nothing beats idly looking around — a verdict about the scene, not a failure.
            if (this.focus == null) {
                this.focus = scan(scene, now, random);
            }
            return;
        }
        if (this.focus == null) {
            this.focus = adopt(best, now);
            return;
        }
        if (best.key().equals(this.focus.key())) {
            return; // already looking at it; its dwell is not restarted by looking harder
        }
        if (best.snap() || best.score() > this.focus.score() * (1.0 + PREEMPT)) {
            forget(now);
            this.focus = adopt(best, now);
        }
    }

    private Focus adopt(Salience.Candidate candidate, long now) {
        return new Focus(candidate.key(), candidate.x(), candidate.y(), candidate.z(),
                now + candidate.dwell(), candidate.score(), candidate.snap(), candidate.reason());
    }

    /**
     * Stop looking at whatever it is, and remember when — which is what makes it less interesting
     * for a while ({@link Salience.Scene#novelty}).
     */
    private void forget(long now) {
        if (this.focus != null && !SCAN_KEY.equals(this.focus.key())) {
            if (this.lastLookedAt.size() >= MEMORY_LIMIT) {
                // The stalest has been un-interesting longest; dropping it is what forgetting does.
                this.lastLookedAt.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .ifPresent(oldest -> this.lastLookedAt.remove(oldest.getKey()));
            }
            this.lastLookedAt.put(this.focus.key(), now);
        }
        this.focus = null;
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

    /** One roll of the idle scan: a bearing off the shoulders, a small tilt, a point out there. */
    private static Focus scan(Salience.Scene scene, long now, RandomGenerator random) {
        int arc = arc(scene.profile());
        boolean forward = random.nextDouble() < SCAN_FORWARD_CHANCE;
        double offset = forward ? 0.0 : random.nextInt(2 * arc + 1) - arc;
        double pitch = random.nextInt(2 * SCAN_PITCH_DEGREES + 1) - SCAN_PITCH_DEGREES;
        double yaw = Math.toRadians(scene.bodyYaw() + offset);
        double tilt = Math.toRadians(pitch);
        // Minecraft's convention, the same one the sense cones are measured in: yaw 0° faces +Z,
        // and a POSITIVE pitch looks down (BeingSensorCore#inCone).
        double horizontal = SCAN_DISTANCE * Math.cos(tilt);
        double x = scene.eyeX() - Math.sin(yaw) * horizontal;
        double z = scene.eyeZ() + Math.cos(yaw) * horizontal;
        double y = scene.eyeY() - SCAN_DISTANCE * Math.sin(tilt);
        int min = scene.profile().i(ProfileAspect.GAZE_SCAN_MIN_TICKS);
        int max = scene.profile().i(ProfileAspect.GAZE_SCAN_MAX_TICKS);
        // A species may declare the pair crossed over: bounds travel with each aspect, but nothing
        // can express "and above the other one". Taking the wider reading beats throwing inside a
        // tick.
        int dwell = max <= min ? Math.max(1, min) : min + random.nextInt(max - min);
        return new Focus(SCAN_KEY, x, y, z, now + dwell, SCAN_SCORE, false,
                forward ? "scan ahead" : String.format(Locale.ROOT, "scan %+.0f°", offset));
    }
}
