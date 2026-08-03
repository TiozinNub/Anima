package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The far sense: the gist of what a body makes out past inspection range — <em>there is forest
 * over there</em>, not a tree with its logs counted.
 *
 * <p><b>It is rays.</b> Each bearing is looked along by a FAN of them, spread in pitch from
 * {@value #PITCH_DOWN_DEGREES}° below the eye to {@value #PITCH_UP_DEGREES}° above and marching
 * cell by cell until something solid stops them — no heightmap arithmetic, no inference. A canopy
 * or a water surface is the third case, {@link BlockProbe.Sight#VEILED}: seen, seen through, and
 * registering as something made out. Without it a wood is invisible — leaves stop nothing, and at
 * fifty blocks the bearings are three apart, so only a one-block trunk could be threaded.
 *
 * <p><b>How far it is seen through depends on how far off it is</b> ({@link #seeThroughRadius}):
 * gaps to carry on between near at hand, a wall past that reach. So a wood arrives as one glimpse
 * at its near edge, and a body inside one has almost no far sense until it climbs out. Rays start
 * <em>at the eye</em>, and a hit nearer than {@code places.radius} is dropped — that ground is the
 * near field's.
 *
 * <p><b>Passive tier</b>, lossy on purpose:
 * <ul>
 *   <li>only bearings inside the head cone are walked;</li>
 *   <li>rays are {@value #MAX_RAY_SPREAD} blocks apart at full reach ({@link #rays} is sized for
 *       that), so saplings and lone logs slip between two of them and a canopy does not;</li>
 *   <li>the fan is fired bottom-up and ends the bearing after {@value #CLEAR_RUN_TO_STOP} rays in
 *       a row reach full range meeting nothing, since for grounded things a clear line means every
 *       line above it is clear — which loses the overhang;</li>
 *   <li>a sighting never triggers the region scan.</li>
 * </ul>
 *
 * <p>Costs one block read per ray step, charged as spent, plus one classifying read per novel
 * coarse cell landed on; no confirm-ray, a ray that arrived having proved its line. Resumable like
 * {@link RegionGrowth} — {@link #step} spends at most a budget and picks up mid-RAY next tick, fed
 * the wallet the near field did not use.
 */
public final class HorizonScanner {

    /**
     * Bins walked per pass. The buffer's 256 bearings suit a full-reach survey and oversample the
     * passive tier's shorter range (1.6 blocks of arc at 64), so every second bearing still leaves
     * only 3.1 blocks between samples — inside the narrowest full-grown canopy. That bound, not
     * cost, caps the passive radius: past about 80 blocks every second bearing would miss a tree,
     * and widening the reach means more bearings, not merely longer ones.
     *
     * <p>Public because two buffer samples are neighbours at this spacing and no closer, so the
     * debug view can join them without inventing ground nobody walked.
     */
    public static final int BIN_STRIDE = 2;

    /**
     * How far below level the fan reaches. Nearly free: a downward ray meets the ground within a
     * handful of blocks, so the lower half costs less than one ray near level — and it is the
     * whole view of a body standing on a cliff or a roof.
     */
    private static final double PITCH_DOWN_DEGREES = 30.0;

    /**
     * How far above level it reaches — the expensive half, an upward ray over open ground paying
     * full reach for nothing. {@value}° is a 23-block rise at 64 blocks: any tree, the shoulder of
     * a hill, and steeper is close enough for the near field.
     */
    private static final double PITCH_UP_DEGREES = 20.0;

    /**
     * The widest the fan may spread at full reach. The narrowest full-grown canopy is 5 blocks, so
     * rays 4 apart cannot pass either side of a real tree — the same line the bin stride draws
     * horizontally, drawn again vertically, and for the same reason.
     */
    private static final int MAX_RAY_SPREAD = 4;

    /**
     * How many consecutive rays must fly clear before the fan gives up on climbing.
     *
     * <p>Fired bottom-up, a ray that reaches full range meeting nothing is strong evidence that
     * everything above it will too — for GROUNDED things. A tree is not: a one-block trunk holds a
     * five-block crown, so a bearing beside the trunk goes clear under the canopy where the ray
     * one step higher would have gone through it. Stopping on the first clear ray missed an oak 48
     * blocks out in plain view; two crosses that gap, at one extra ray into empty sky per bearing.
     */
    private static final int CLEAR_RUN_TO_STOP = 2;

    /**
     * A bearing looked along this recently is left alone.
     *
     * <p>Ten seconds, set against a MEASURED sweep: one full pass of the cone at a Person's reach
     * costs about 10,200 reads against a 64-a-tick wallet — roughly 160 ticks even when the near
     * field hands over all of it. Anything shorter is no shorter at all: bearings go stale faster
     * than the sweep reaches them, so it never finishes and never stops spending. Thirty seconds
     * went the other way, a body unable to notice a tree grown in front of it.
     *
     * <p>The dials are {@code limits.reads_per_tick} and {@code places.horizon_radius} (the reach,
     * which sets both ray length and fan size).
     */
    public static final int REFRESH_TICKS = 200;

    /** Moving this far from where the readout was taken marks every bearing stale. */
    private static final int REANCHOR_DISTANCE = 16;

    /** Sightings are deduplicated on a grid this coarse — a forest is one glimpse, not two hundred. */
    private static final int GLIMPSE_GRID = 8;

    /** How many of those cells are remembered before the oldest is allowed round again. */
    private static final int GLIMPSE_MEMORY = 256;

    /** Eye height as a fraction of body height — a little below the crown. */
    private static final double EYE_FRACTION = 0.85;

    /** How far this body makes out a skyline at all. 0 disables the sense entirely. */
    public static int radius(AgentProfile profile) {
        return profile.i(ProfileAspect.PLACES_HORIZON_RADIUS);
    }

    /**
     * How far this body's eye resolves a {@link BlockProbe.Sight#VEILED} thing into its parts —
     * past which the ray stops on it rather than carrying through.
     *
     * <p>Not shared with the near field's confirm ray, which fires up to
     * {@link CrescentSampler#radius} through the canopy of the very tree it is hypothesizing: that
     * one asks <em>could I make out that particular cell</em>, this one <em>does the view carry
     * past here</em>.
     */
    public static int seeThroughRadius(AgentProfile profile) {
        return profile.i(ProfileAspect.PLACES_SEE_THROUGH_RADIUS);
    }

    /**
     * Whether the view still carries from an eye to one particular cell — the sweep's own
     * question, asked about a single target instead of along a bearing.
     *
     * <p>Here because the rule belongs to the sweep: anything asking "can they still see that" of
     * a far-sense report must apply the same see-through reach. The near field's confirm-ray says
     * yes through any amount of canopy at any distance, and once drew a debug sight line clean
     * through forty blocks of wood. Marched with the same whole-block flat steps and the same
     * {@link BlockProbe#sightAt} the sweep uses, so a line it allows is a line a ray could fly.
     */
    public static boolean viewCarriesTo(BlockProbe probe, double eyeX, double eyeY, double eyeZ,
            Pos target, int seeThroughRadius) {
        double dx = target.x() + 0.5 - eyeX;
        double dz = target.z() + 0.5 - eyeZ;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0) {
            return true; // they are standing in it
        }
        double stepX = dx / flat;
        double stepZ = dz / flat;
        double rise = (target.y() + 0.5 - eyeY) / flat;
        for (int d = 1; d <= (int) flat; d++) {
            int x = (int) Math.round(eyeX + stepX * d);
            int z = (int) Math.round(eyeZ + stepZ * d);
            int y = (int) Math.floor(eyeY + rise * d);
            if (x == target.x() && y == target.y() && z == target.z()) {
                return true; // arrived: the target itself never blocks the look at it
            }
            switch (probe.sightAt(x, y, z)) {
                case BLOCKED, OUTSIDE -> {
                    return false;
                }
                case VEILED -> {
                    if (d > seeThroughRadius) {
                        return false;
                    }
                }
                case CLEAR -> { }
            }
        }
        return true;
    }

    /**
     * How many rays make up one bearing's fan at this reach — enough that neighbours are never
     * more than {@link #MAX_RAY_SPREAD} apart at the far end, where they are widest. A
     * shorter-sighted body gets fewer.
     */
    public static int rays(int radius) {
        if (radius <= 0) {
            return 0;
        }
        double perRay = Math.toDegrees(Math.atan((double) MAX_RAY_SPREAD / radius));
        return 1 + (int) Math.ceil((PITCH_DOWN_DEGREES + PITCH_UP_DEGREES) / perRay);
    }

    /** The pitch of one ray of a fan of {@code rays}, in degrees, negative downward. */
    public static double pitchOf(int ray, int rays) {
        if (rays <= 1) {
            return 0.0;
        }
        double span = PITCH_DOWN_DEGREES + PITCH_UP_DEGREES;
        return -PITCH_DOWN_DEGREES + span * ray / (rays - 1);
    }

    private final AgentProfile profile;
    private final HorizonBuffer buffer = new HorizonBuffer();

    /** Bearing being looked along, or −1 between bearings. */
    private int bin = -1;
    /** Which ray of that bearing's fan is in flight. */
    private int ray;
    /** How many there are — fixed for the bearing, since the reach is. */
    private int rayCount;
    /** How far out that ray has got, measured flat, in whole blocks. */
    private int distance;
    /** Its rise per block of flat travel. */
    private double pitchTan;
    /**
     * Where the fan was fired from, snapshotted per bearing: a bearing finishes inside a tick or
     * two, and firing one fan from one place keeps its occlusion coherent.
     */
    private int originX;
    private int originZ;
    private double originEyeY;
    /** The flat direction of the bearing in flight. */
    private double dirX;
    private double dirZ;

    /** How many rays in a row have flown clear — see {@link #CLEAR_RUN_TO_STOP}. */
    private int clearRun;

    /** The steepest thing any ray of this fan has stopped on, and where. */
    private boolean anyCrest;
    private double crestTan;
    private int crestX;
    private int crestY;
    private int crestZ;
    /** Any ray of this fan ran out of loaded world. */
    private boolean cutShort;

    /** One coarse cell, as reported for one kind of thing — see {@link #answered}. */
    private record Reported(PoiKind kind, long cell) {
    }

    /**
     * Coarse cells already reported, newest-used last — what keeps a wood from being announced
     * again on every pass.
     *
     * <p><b>Keyed by kind as well as cell</b>: a cell-only memory lets whichever kind was seen
     * First mask every other one there forever. The cost is that the classifying read happens
     * Before the lookup (nothing can say which kind a landing is until it has read the block), so
     * an already-answered cell costs one read where it used to cost none.
     *
     * <p>SIGHTINGS only: remembering an absence is a body deciding, permanently, that nothing will
     * ever appear on ground it has already looked at.
     */
    private final Map<Reported, Boolean> answered = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Reported, Boolean> eldest) {
            return size() > GLIMPSE_MEMORY;
        }
    };

    public HorizonScanner(AgentProfile profile) {
        this.profile = profile;
    }

    /** The readout, for the debug view and for whatever comes to reason about vantages. */
    public HorizonBuffer buffer() {
        return this.buffer;
    }

    /**
     * Looks along the skyline for at most {@code budget} reads and returns what was actually
     * spent. One step may overrun by the read that classifies what it landed on: the wallet is a
     * per-tick target, not a hard gate.
     */
    public int step(Pos feet, double yawDegrees, long now, BlockProbe probe, int budget,
            List<SenseEvent> events) {
        int radius = radius(this.profile);
        int near = CrescentSampler.radius(this.profile);
        if (radius <= near || budget <= 0) {
            return 0; // no skyline to speak of: the near field already reaches as far
        }
        Column here = new Column(feet.x(), feet.z());
        if (this.buffer.anchor() == null || moved(this.buffer.anchor(), here)) {
            this.buffer.reanchor(here);
        }
        int seeThrough = seeThroughRadius(this.profile);
        int reads = 0;
        while (reads < budget) {
            if (this.bin < 0) {
                int next = nextBearing(yawDegrees, now);
                if (next < 0) {
                    break; // everything in front is fresh; the sense costs nothing
                }
                begin(next, feet, radius);
            }
            reads += march(probe, radius, near, seeThrough, now, events);
        }
        return reads;
    }

    /**
     * One cell of the ray in flight. Stepping by whole blocks of FLAT distance keeps the
     * arithmetic to one multiply and one read per step, and within the fan's pitch range a step
     * lifts the ray at most 0.58 blocks, so it cannot skip a cell vertically. It can still squeeze
     * diagonally past a corner — this gates <em>noticing</em>, not physics.
     */
    private int march(BlockProbe probe, int radius, int near, int seeThrough, long now,
            List<SenseEvent> events) {
        int d = this.distance;
        int x = (int) Math.round(this.originX + this.dirX * d);
        int z = (int) Math.round(this.originZ + this.dirZ * d);
        int y = (int) Math.floor(this.originEyeY + this.pitchTan * d);
        int reads = 1;
        switch (probe.sightAt(x, y, z)) {
            case OUTSIDE -> {
                // Every remaining ray walks the same columns and would stop at the same edge, so
                // the whole bearing ends here.
                this.cutShort = true;
                endBearing(now);
            }
            case BLOCKED -> {
                // Nearer than inspection range is the near field's ground: being stopped by it is
                // the point (it is what hides the distance), but reporting it is not.
                this.clearRun = 0;
                if (d > near) {
                    reads += land(probe, x, y, z, d, events);
                }
                endRay(now);
            }
            case VEILED -> {
                // Seen, and seen through only while near enough to have parts: close up a canopy
                // is branches and gaps, at forty blocks a green wall. Past the threshold this is
                // the BLOCKED case — the thing still registers (a wood is made of leaves;
                // refusing to stop on them would find only trunks) and the ray dies on it.
                this.clearRun = 0;
                if (d > near) {
                    reads += land(probe, x, y, z, d, events);
                }
                if (d > seeThrough) {
                    endRay(now);
                } else {
                    this.distance++;
                    if (this.distance > radius) {
                        endRay(now);
                    }
                }
            }
            case CLEAR -> {
                this.distance++;
                if (this.distance > radius) {
                    this.clearRun++;
                    if (this.clearRun >= CLEAR_RUN_TO_STOP) {
                        endBearing(now);
                    } else {
                        endRay(now);
                    }
                }
            }
        }
        return reads;
    }

    /**
     * A ray stopped on something out in the world: the steepest such stop tops the bearing, and
     * anything the growth rules recognise becomes a glimpse. No confirm-ray — the ray arrived, so
     * its line is clear by construction. That is what makes the fan affordable.
     */
    private int land(BlockProbe probe, int x, int y, int z, int distance, List<SenseEvent> events) {
        double tan = (y - this.originEyeY) / distance;
        if (!this.anyCrest || tan > this.crestTan) {
            this.anyCrest = true;
            this.crestTan = tan;
            this.crestX = x;
            this.crestY = y;
            this.crestZ = z;
        }
        BlockKind kind = probe.at(x, y, z);
        GrowthRule rule = GrowthRules.forSeed(kind).orElse(null);
        if (rule == null) {
            // Not remembered: marking plain ground answered would mean a body that
            // once looked at a hillside can never notice a sapling grow on it. Only a thing is
            // remembered, never an absence, at one classifying read per landing.
            return 1;
        }
        if (this.answered.putIfAbsent(new Reported(rule.kind(), cellOf(x, z)), true) == null) {
            events.add(SenseEvent.glimpsed(rule.kind(), new Pos(x, y, z)));
        }
        return 1;
    }

    /**
     * The stalest bearing inside the head cone, nearest the middle of it on a tie. Bearings behind
     * are never candidates.
     */
    private int nextBearing(double yawDegrees, long now) {
        double half = CrescentSampler.coneDegrees(this.profile) / 2.0;
        int best = -1;
        long bestSwept = Long.MAX_VALUE;
        double bestOffset = Double.MAX_VALUE;
        for (int candidate = 0; candidate < HorizonBuffer.BINS; candidate += BIN_STRIDE) {
            double offset = Math.abs(
                    HorizonBuffer.angleDelta(HorizonBuffer.bearingOf(candidate), yawDegrees));
            if (offset > half) {
                continue;
            }
            if (this.buffer.isFresh(candidate, now, REFRESH_TICKS)) {
                continue;
            }
            long swept = this.buffer.staleness(candidate);
            if (swept < bestSwept || (swept == bestSwept && offset < bestOffset)) {
                best = candidate;
                bestSwept = swept;
                bestOffset = offset;
            }
        }
        return best;
    }

    private void begin(int bearing, Pos feet, int radius) {
        this.bin = bearing;
        this.originX = feet.x();
        this.originZ = feet.z();
        this.originEyeY = feet.y() + this.profile.i(ProfileAspect.BODY_HEIGHT) * EYE_FRACTION;
        // Minecraft's convention, shared with the being sense's cone: yaw 0° faces +Z.
        double radians = Math.toRadians(HorizonBuffer.bearingOf(bearing));
        this.dirX = -Math.sin(radians);
        this.dirZ = Math.cos(radians);
        this.rayCount = rays(radius);
        this.ray = 0;
        this.clearRun = 0;
        this.anyCrest = false;
        this.crestTan = 0.0;
        this.cutShort = false;
        aim();
    }

    /** Points the next ray of the fan and puts it back at the eye. */
    private void aim() {
        this.distance = 1;
        this.pitchTan = Math.tan(Math.toRadians(pitchOf(this.ray, this.rayCount)));
    }

    private void endRay(long now) {
        this.ray++;
        if (this.ray < this.rayCount) {
            aim();
        } else {
            endBearing(now);
        }
    }

    /**
     * The whole fan has been fired. The steepest thing it found tops the bearing; a fan that found
     * nothing BLANKS it rather than leaving the last sweep's crest standing — the usual way to
     * produce an empty bearing is to fell what was on it.
     */
    private void endBearing(long now) {
        if (this.anyCrest) {
            this.buffer.record(this.bin, this.crestTan, this.crestX, this.crestY, this.crestZ);
        } else {
            this.buffer.blank(this.bin);
        }
        this.buffer.markSwept(this.bin, now, this.cutShort);
        this.bin = -1;
    }

    private static boolean moved(Column anchor, Column here) {
        long dx = (long) anchor.x() - here.x();
        long dz = (long) anchor.z() - here.z();
        return dx * dx + dz * dz > (long) REANCHOR_DISTANCE * REANCHOR_DISTANCE;
    }

    /** Sightings collapse onto a coarse grid. That is what makes a forest one glimpse. */
    private static long cellOf(int x, int z) {
        long gx = Math.floorDiv(x, GLIMPSE_GRID);
        long gz = Math.floorDiv(z, GLIMPSE_GRID);
        return (gx & 0xffffffffL) << 32 | (gz & 0xffffffffL);
    }
}
