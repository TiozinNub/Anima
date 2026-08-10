package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A deliberate look round — the active tier to {@link HorizonScanner}'s passive one, which is
 * lossy three ways on purpose: never behind, every second bearing, and only what STOPPED a ray.
 *
 * <ul>
 *   <li><b>All the way round</b>, not the head cone: the body is turning on the spot.</li>
 *   <li><b>Every bearing</b> — twice the angular resolution. That is what brings anything
 *       narrower than a canopy within reach of being hit at all.</li>
 *   <li><b>It asks what it passed THROUGH.</b> {@link BlockProbe.Sight#THIN} — grass, a flower, a
 *       cane stalk — registers here and nowhere else: sugar cane is collision-free, so no density
 *       of rays would ever find it. The mechanism had to change, not the budget.</li>
 *   <li><b>It finishes</b>, where a passive sweep is only ever more or less fresh — which is what
 *       concluding anything from an ABSENCE needs.</li>
 * </ul>
 *
 * <p>Everything else it inherits from the passive tier's geometry: pitch spread and ray count
 * ({@link HorizonScanner#rays}), bottom-up firing that stops once the sky is clear, the
 * see-through reach that makes a far canopy a wall, and the rule that anything nearer than
 * {@code places.radius} is the near field's and only an obstruction here.
 *
 * <p>Four to five times a passive cone sweep — about 10,200 reads for a Person, so figure fifty
 * thousand for a survey at the same reach. Too much to do while walking, so a command drives it;
 * the scheduling question (whose budget, how often, standing where) is still open. Resumable,
 * though nothing yet spreads it.
 */
public final class Survey {

    /** Sightings collapse onto this grid, as the passive tier's do — a wood is one glimpse. */
    private static final int GLIMPSE_GRID = 8;

    /** Eye height as a fraction of body height — a little below the crown, as the fan uses. */
    private static final double EYE_FRACTION = 0.85;

    /** One coarse cell, as reported for one kind — the same dedup the passive tier keeps. */
    private record Reported(PoiKind kind, long cell) {
    }

    private final AgentProfile profile;
    private final Pos from;
    private final double eyeY;
    private final int radius;
    private final int near;
    private final int seeThrough;
    private final int rayCount;

    private final Set<Reported> answered = new HashSet<>();

    /** Bearing being walked. Starts at 0 and ends at {@link HorizonBuffer#BINS}. */
    private int bin;
    private int ray;
    private int distance = 1;
    private double pitchTan;
    private double dirX;
    private double dirZ;
    private int clearRun;

    /**
     * @param from where the body is standing — fixed for the whole sweep, so its occlusion stays
     *             coherent even if a caller spreads the work over several ticks
     */
    public Survey(AgentProfile profile, Pos from) {
        this.profile = profile;
        this.from = from;
        // Rounded up to whole cells: the eye fraction was tuned against a Person
        // counted as 2 cells, and a sense's reach is not retuned as a side effect of a NAVIGATION
        // change. The true eye (1.62 on a 1.8 body — 0.9 of it, not 0.85) is its own slice.
        this.eyeY = from.y()
                + Math.ceil(profile.d(ProfileAspect.BODY_HEIGHT)) * EYE_FRACTION;
        this.radius = HorizonScanner.radius(profile);
        this.near = CrescentSampler.radius(profile);
        this.seeThrough = HorizonScanner.seeThroughRadius(profile);
        this.rayCount = HorizonScanner.rays(this.radius);
        aimBearing();
    }

    /** Whether this body can survey at all — a reach no longer than the near field is no reach. */
    public boolean possible() {
        return this.radius > this.near && this.rayCount > 0;
    }

    /** Whether the whole turn has been made. */
    public boolean done() {
        return this.bin >= HorizonBuffer.BINS;
    }

    /** How far round it has got, 0–100, for something to report while it works. */
    public int progress() {
        return Math.min(100, this.bin * 100 / HorizonBuffer.BINS);
    }

    /**
     * Looks for at most {@code budget} reads and returns what was spent. Call until {@link #done}.
     *
     * <p>One step may overrun by the read that classifies what a ray landed on — the budget is a
     * target, not a gate, exactly as the passive sweep's wallet is.
     */
    public int step(BlockProbe probe, int budget, List<SenseEvent> events) {
        if (!possible()) {
            this.bin = HorizonBuffer.BINS;
            return 0;
        }
        int reads = 0;
        while (reads < budget && !done()) {
            reads += march(probe, events);
        }
        return reads;
    }

    /** One cell of the ray in flight — the passive tier's march, with one more thing to notice. */
    private int march(BlockProbe probe, List<SenseEvent> events) {
        int d = this.distance;
        int x = (int) Math.round(this.from.x() + this.dirX * d);
        int z = (int) Math.round(this.from.z() + this.dirZ * d);
        int y = (int) Math.floor(this.eyeY + this.pitchTan * d);
        int reads = 1;
        switch (probe.sightAt(x, y, z)) {
            case OUTSIDE -> {
                // Every remaining ray walks the same columns and stops at the same edge, so the
                // bearing is finished.
                endBearing();
            }
            case BLOCKED -> {
                this.clearRun = 0;
                if (d > this.near) {
                    reads += land(probe, x, y, z, events);
                }
                endRay();
            }
            case VEILED -> {
                this.clearRun = 0;
                if (d > this.near) {
                    reads += land(probe, x, y, z, events);
                }
                if (d > this.seeThrough) {
                    endRay(); // a canopy at range is a wall to a deliberate look too
                } else {
                    advance();
                }
            }
            case THIN -> {
                // The whole reason this tier exists: too slight to stop any ray, so only a body
                // that has stopped to look round pays the read that names it. A cane brake is
                // found here and nowhere else.
                this.clearRun = 0;
                if (d > this.near) {
                    reads += land(probe, x, y, z, events);
                }
                advance();
            }
            case CLEAR -> {
                if (this.distance + 1 > this.radius) {
                    this.clearRun++;
                    if (this.clearRun >= HorizonScanner.CLEAR_RUN_TO_STOP) {
                        endBearing();
                        return reads;
                    }
                    endRay();
                    return reads;
                }
                advance();
            }
        }
        return reads;
    }

    /** Classifies what a ray met, and reports it once per kind per coarse cell. */
    private int land(BlockProbe probe, int x, int y, int z, List<SenseEvent> events) {
        BlockKind kind = probe.at(x, y, z);
        GrowthRule rule = GrowthRules.forSeed(kind).orElse(null);
        if (rule == null) {
            return 1; // plain ground, and never remembered as such — see HorizonScanner.land
        }
        if (this.answered.add(new Reported(rule.kind(), cellOf(x, z)))) {
            events.add(SenseEvent.glimpsed(rule.kind(), new Pos(x, y, z)));
        }
        return 1;
    }

    private void advance() {
        this.distance++;
        if (this.distance > this.radius) {
            endRay();
        }
    }

    private void endRay() {
        this.ray++;
        if (this.ray < this.rayCount) {
            this.distance = 1;
            this.pitchTan = Math.tan(Math.toRadians(HorizonScanner.pitchOf(this.ray, this.rayCount)));
        } else {
            endBearing();
        }
    }

    private void endBearing() {
        this.bin++;
        if (!done()) {
            aimBearing();
        }
    }

    /** Points the fan down the next bearing. Every bin, where the passive sweep takes every second. */
    private void aimBearing() {
        // Minecraft's convention, shared with the being sense's cone: yaw 0° faces +Z.
        double radians = Math.toRadians(HorizonBuffer.bearingOf(this.bin));
        this.dirX = -Math.sin(radians);
        this.dirZ = Math.cos(radians);
        this.ray = 0;
        this.clearRun = 0;
        this.distance = 1;
        this.pitchTan = Math.tan(Math.toRadians(HorizonScanner.pitchOf(0, Math.max(this.rayCount, 1))));
    }

    private static long cellOf(int x, int z) {
        long gx = Math.floorDiv(x, GLIMPSE_GRID);
        long gz = Math.floorDiv(z, GLIMPSE_GRID);
        return (gx & 0xffffffffL) << 32 | (gz & 0xffffffffL);
    }
}
