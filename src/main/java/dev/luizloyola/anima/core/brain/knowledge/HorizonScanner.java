package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The far sense: what a body makes out past the range at which it could inspect anything — the
 * gist, not the thing. Where the near field individuates a tree and counts its logs, this says only
 * <em>there is forest over there</em>.
 *
 * <p><b>Passive tier.</b> Unbidden, forward-only, and lossy on purpose in three ways:
 * <ul>
 *   <li><b>It never looks behind.</b> Only bearings inside the head cone are walked; the active
 *       tier exists for the rest.</li>
 *   <li><b>Sample spacing grows with distance</b> ({@link #stride}), holding roughly constant
 *       angular resolution the way an eye does. Below about four blocks of feature width nothing
 *       registers, so saplings and lone logs are invisible at range.</li>
 *   <li><b>Nothing grows.</b> A sighting never triggers the region scan.</li>
 * </ul>
 *
 * <p><b>It costs no rays to see terrain.</b> Occlusion comes out of the running maximum described
 * on {@link HorizonBuffer}. Rays are spent only on a <em>candidate sighting</em> — cheap
 * hypothesis, one confirming ray, the near field's own shape — at {@value #HORIZON_RAY_COST}
 * rather than 8, because the walk is twice as long.
 *
 * <p>Resumable like {@link RegionGrowth}: {@link #step} spends at most a budget of reads and picks
 * up mid-bearing next tick, fed the wallet the near field did not use — so a body crossing new
 * ground head-down scans no horizon and a standing body scans with its whole budget.
 */
public final class HorizonScanner {

    /**
     * Bins walked per pass. The buffer's 256 bearings are sized for a full-reach survey; the
     * passive tier works at a shorter range where that is heavy oversampling (1.2 blocks of arc
     * at 48), so it takes every second bearing and still leaves 2.4 blocks between samples —
     * inside the narrowest canopy.
     */
    private static final int BIN_STRIDE = 2;

    /** Sample spacing is {@code distance / this}, so angular resolution stays constant. */
    private static final int STRIDE_DIVISOR = 12;

    /**
     * Spacing never exceeds this. The narrowest full-grown canopy is 5 wide, so a stride of 4
     * cannot step over a real tree — which is the line between "misses saplings", which
     * is acceptable, and "misses forests", which is the bug this whole sense exists to fix.
     */
    private static final int MAX_STRIDE = 4;

    /** Wallet charge for one far confirm-ray — twice the near field's, for twice the walk. */
    public static final int HORIZON_RAY_COST = 16;

    /**
     * A bearing walked this recently is left alone. Without it a body with spare budget re-walks
     * the same skyline every tick forever, learning nothing and spending everything.
     */
    public static final int REFRESH_TICKS = 100;

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

    private final AgentProfile profile;
    private final HorizonBuffer buffer = new HorizonBuffer();

    /** Bearing being walked, or −1 between bearings. */
    private int bin = -1;
    /** How far along that bearing the walk has got. */
    private int distance;
    /** Running steepest-so-far for the bearing in progress — the occlusion. */
    private double running;
    /**
     * Where the walk started, snapshotted per bearing. A bearing finishes inside a tick or two,
     * so the body has moved a fraction of a block by the end; sampling one bearing from one place
     * keeps its occlusion coherent, which sampling from wherever the feet happen to be would not.
     */
    private int originX;
    private int originZ;
    private double originEyeY;

    /**
     * Coarse cells already answered for, newest-used last. Holds refusals as well as sightings:
     * a candidate whose confirm-ray failed is not worth re-raying at {@value #HORIZON_RAY_COST}
     * every sweep, and the near field will meet it properly soon enough if the body goes that way.
     */
    private final Map<Long, Boolean> answered = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
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
     * Walks the skyline for at most {@code budget} reads and returns what was actually spent.
     *
     * <p>One sample may overrun the budget by its confirm-ray, the same way a region scan may
     * overrun by a neighbour — the wallet is a per-tick target, not a hard gate, and the caller's
     * ceiling carries the slack.
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
        int reads = 0;
        while (reads < budget) {
            if (this.bin < 0) {
                int next = nextBearing(yawDegrees, now);
                if (next < 0) {
                    break; // everything in front is fresh; the sense costs nothing
                }
                begin(next, feet, near);
            }
            reads += sample(probe, radius, now, events);
        }
        return reads;
    }

    /** One column along the bearing in progress. */
    private int sample(BlockProbe probe, int radius, long now, List<SenseEvent> events) {
        double radians = Math.toRadians(HorizonBuffer.bearingOf(this.bin));
        // Minecraft's convention, shared with the being sense's cone: yaw 0° faces +Z.
        double dirX = -Math.sin(radians);
        double dirZ = Math.cos(radians);
        int x = (int) Math.round(this.originX + dirX * this.distance);
        int z = (int) Math.round(this.originZ + dirZ * this.distance);
        int reads = 1;
        int y = probe.surfaceY(x, z);
        if (y == Integer.MIN_VALUE) {
            // The world stops being loaded here. The bearing ends there rather than
            // pretending the rest of it is empty — see the survey's completeness record.
            end(now, true);
            return reads;
        }
        double elevation = (y - this.originEyeY) / this.distance;
        if (elevation > this.running) {
            this.running = elevation;
            this.buffer.record(this.bin, elevation, x, y, z);
            reads += consider(probe, x, y, z, events);
        }
        this.distance += stride(this.distance);
        if (this.distance > radius) {
            end(now, false);
        }
        return reads;
    }

    /**
     * A column that cleared the skyline — is it something, and can it actually be seen?
     *
     * <p>Mostly redundant beside the running maximum; it earns its cost on the two things the walk
     * cannot see: occluders nearer than {@code places.radius} (a barn ten blocks off hides a wood
     * forty blocks off) and anything narrower than the sample stride at range. Charged at the same
     * fiction-to-truth ratio the near field's 8 uses.
     */
    private int consider(BlockProbe probe, int x, int y, int z, List<SenseEvent> events) {
        BlockKind kind = probe.at(x, y, z);
        int reads = 1;
        GrowthRule rule = GrowthRules.forSeed(kind).orElse(null);
        if (rule == null) {
            return reads;
        }
        long cell = cellOf(x, z);
        if (this.answered.containsKey(cell)) {
            return reads;
        }
        Pos at = new Pos(x, y, z);
        reads += HORIZON_RAY_COST;
        boolean seen = probe.visibleFromEyes(at);
        this.answered.put(cell, seen);
        if (seen) {
            events.add(SenseEvent.glimpsed(rule.kind(), at));
        }
        return reads;
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

    private void begin(int bearing, Pos feet, int near) {
        this.bin = bearing;
        this.originX = feet.x();
        this.originZ = feet.z();
        this.originEyeY = feet.y() + this.profile.i(ProfileAspect.BODY_HEIGHT) * EYE_FRACTION;
        this.distance = near;
        this.running = Double.NEGATIVE_INFINITY;
    }

    private void end(long now, boolean cutShort) {
        this.buffer.markSwept(this.bin, now, cutShort);
        this.bin = -1;
    }

    /** Sample spacing at a distance: constant angular resolution, capped at a canopy's width. */
    private static int stride(int distance) {
        return Math.max(1, Math.min(MAX_STRIDE, distance / STRIDE_DIVISOR));
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
