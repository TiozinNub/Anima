package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * What a body can make out on the skyline, one entry per bearing: the <em>steepest thing seen in
 * that direction</em>, and where it stands.
 *
 * <p>Long sight costs no rays. Walking outward and keeping a running maximum of
 * {@code tan(elevation)} registers a column only if it rises above everything nearer on the same
 * line, so hills occlude — arithmetic over a heightmap the near field already reads. (The old
 * voxel-terrain horizon walk; terrain LOD calls the quantity the horizon angle.)
 *
 * <p><b>Bearings are absolute, not head-relative.</b> Bin 0 is 0° (+Z, Minecraft's convention) and
 * the head cone selects a slice, so a body turns without discarding what it made out, and the
 * passive sweep and the full-circle survey share one structure.
 *
 * <p>Occlusion accumulates only from {@code places.radius} outward, so a wall at arm's length does
 * not darken a bearing; sightings are gated by a confirm-ray instead (see
 * {@link HorizonScanner}). Nor is it a memory — a live readout, and everything worth keeping leaves
 * it as an event.
 */
public final class HorizonBuffer {

    /**
     * Bearing resolution. 256 bins put 1.4° between bearings — 3.1 blocks of arc at 128, which
     * is inside the narrowest full-grown canopy, so a survey at full reach cannot slip between
     * two bearings. The passive tier oversamples at its own shorter range and strides bins
     * instead of paying for that.
     */
    public static final int BINS = 256;

    private static final double DEGREES_PER_BIN = 360.0 / BINS;

    /** Steepest elevation seen along each bearing, as a tangent. */
    private final float[] tan = new float[BINS];
    private final int[] topX = new int[BINS];
    private final int[] topY = new int[BINS];
    private final int[] topZ = new int[BINS];
    /** When each bearing was last walked to completion. Meaningless unless {@link #swept}. */
    private final long[] sweptAt = new long[BINS];
    /**
     * Whether each bearing has been walked since the last re-anchor. A separate flag rather than
     * a sentinel time: a brand-new world's game time is 0, so any "0 means never" scheme leaves
     * the first bearings of a body's life looking permanently unswept.
     */
    private final boolean[] swept = new boolean[BINS];
    /** Whether a bearing holds anything at all. */
    private final boolean[] filled = new boolean[BINS];

    /** Where the readout was taken from; null until the first sweep. */
    private Column anchor;

    /** The bin covering a bearing, wrapped into range. */
    public static int binOf(double degrees) {
        int raw = (int) Math.floor(degrees / DEGREES_PER_BIN);
        return ((raw % BINS) + BINS) % BINS;
    }

    /** The centre bearing of a bin, in degrees. */
    public static double bearingOf(int bin) {
        return (bin + 0.5) * DEGREES_PER_BIN;
    }

    /** Signed shortest turn from {@code b} to {@code a}, in (−180, 180]. */
    public static double angleDelta(double a, double b) {
        double delta = (a - b) % 360.0;
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return delta;
    }

    /** Where this readout was taken from, or null before the first sweep. */
    public Column anchor() {
        return this.anchor;
    }

    /**
     * Moves the readout to a new standing place. The entries are KEPT — a skyline seen from
     * sixteen blocks away is still approximately this skyline, and blanking it would leave a body
     * briefly convinced it was walled in on every side — but every bearing is marked stale, so
     * the sweep re-walks them in priority order.
     */
    public void reanchor(Column at) {
        this.anchor = at;
        java.util.Arrays.fill(this.swept, false);
    }

    /** A bearing's steepest sighting rose above everything nearer on the line. */
    public void record(int bin, double elevationTan, int x, int y, int z) {
        this.tan[bin] = (float) elevationTan;
        this.topX[bin] = x;
        this.topY[bin] = y;
        this.topZ[bin] = z;
        this.filled[bin] = true;
    }

    /** A bearing was walked to its end (or to the edge of what is loaded). */
    public void markSwept(int bin, long now) {
        this.sweptAt[bin] = now;
        this.swept[bin] = true;
    }

    /** Whether this bearing has been walked since the last re-anchor. */
    public boolean wasSwept(int bin) {
        return this.swept[bin];
    }

    /** Whether this bearing was walked recently enough to leave alone. */
    public boolean isFresh(int bin, long now, long within) {
        return this.swept[bin] && now - this.sweptAt[bin] < within;
    }

    /**
     * Sort key for "walk the stalest first": the time it was last walked, or before all possible
     * times if it never has been.
     */
    public long staleness(int bin) {
        return this.swept[bin] ? this.sweptAt[bin] : Long.MIN_VALUE;
    }

    /** Whether anything has ever been made out along this bearing. */
    public boolean filled(int bin) {
        return this.filled[bin];
    }

    /** Steepest elevation along this bearing, as a tangent. Meaningless unless {@link #filled}. */
    public double tan(int bin) {
        return this.tan[bin];
    }

    /** The cell that tops this bearing, or null if nothing has been made out along it. */
    public Pos top(int bin) {
        return this.filled[bin] ? new Pos(this.topX[bin], this.topY[bin], this.topZ[bin]) : null;
    }
}
