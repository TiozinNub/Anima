package dev.luizloyola.anima.core.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The ground under the trees over a sampled box, judged against a set of {@link TerrainRules}:
 * which columns are flat, which belong to an area wide enough to use, and where a building fits.
 *
 * <p>Four steps, each a window over the raster and each costing the same everywhere through
 * summed-area tables:
 * <ol>
 *   <li><b>Bare ground.</b> A 3×3 opening (minimum, then maximum) removes anything 1–2 blocks
 *       wide standing on the surface — trunks, branches. Only a cut of 2 or more is taken, so a
 *       one-block bump in real ground survives.</li>
 *   <li><b>Smoothing.</b> Each height becomes the mean of the valid heights around it. A slope
 *       survives that and is refused; a one-block hole does not, and is flat.</li>
 *   <li><b>Areas.</b> The union of every square of {@code areaSize} that fits inside flat cells —
 *       wide, not merely large. A clearing is the same over flat cells nothing stands on.</li>
 *   <li><b>Sites.</b> A plane fitted to every footprint. Per cell, a single step in a meadow and a
 *       steady slope look alike; across a footprint they do not.</li>
 * </ol>
 *
 * <p>Steep ground and cliffs are judged on the bare ground, never the smoothed: at the default
 * radius smoothing turns a ten-block wall into a ramp of one in 1.2.
 *
 * <p>Fluid, used and unknown ground takes no part in smoothing and refuses any area or site.
 */
public final class Terrain {

    /** What a column is, most decisive first. */
    public enum Kind {
        /** In a chunk that was not loaded. */
        UNKNOWN,
        FLUID,
        /** Marked by a block somebody put there, or within the margin of one. */
        USED,
        /** The top of a near-vertical wall: the ground drops the cliff height or more beside it. */
        CLIFF,
        /**
         * A hillside: the ground climbs the steep height at the steep angle or more, up to a top
         * that stands above the land around it.
         */
        STEEP,
        /** Too sloped or too rough to be flat, after smoothing, but not steep. */
        UNEVEN,
        /** Flat, but no square of the area size fits around it. */
        FLAT,
        /** Inside a square of flat ground; something may stand on it. */
        FLAT_AREA,
        /** Inside a square of flat ground nothing stands on. */
        CLEARING
    }

    /**
     * Where a {@code size}×{@code size} footprint fits, centred on {@code (x, z)}.
     *
     * @param y           the fitted plane's height at the centre
     * @param tilt        the plane's rise per block
     * @param preparation blocks to fill and cut to bring the ground onto the plane
     * @param levelling   blocks to fill and cut to bring it level at {@code y}, for a caller that
     *                    needs a flat floor
     * @param trees       trunks standing inside it
     * @param cost        {@code preparation} plus {@code trees} at the rules' tree cost
     */
    public record Site(int x, int z, int size, double y, double tilt, int preparation,
                       int levelling, int trees, double cost) {
    }

    /**
     * A {@code width}×{@code depth} footprint with its north-west corner at {@code (minX, minZ)}.
     *
     * @param y         the mean ground height under it
     * @param tilt      the fitted plane's rise per block
     * @param levelling blocks to fill and cut to bring it level at {@code round(y)}
     * @param trees     trunks standing inside it
     */
    public record Rect(int minX, int minZ, int width, int depth, double y, double tilt, int levelling,
                       int trees) {
    }

    /**
     * Every placement of one footprint size over the read, by its corner: which are allowed — every
     * column known, dry and unused, the plane within a tilt — and what each would cost to level.
     * Built once per size, so a chooser trying thousands of placements pays per placement only a
     * table lookup.
     */
    public final class Rects {
        private final Fits fits;

        private Rects(Fits fits) {
            this.fits = fits;
        }

        public int width() {
            return this.fits.width;
        }

        public int depth() {
            return this.fits.depth;
        }

        /** Whether the footprint with its corner at {@code (minX, minZ)} is allowed. */
        public boolean allowed(int minX, int minZ) {
            int at = this.fits.corner(minX - Terrain.this.minX, minZ - Terrain.this.minZ);
            return at >= 0 && !Double.isNaN(this.fits.a[at]);
        }

        /**
         * An order to shortlist by, not a count: the area times the root mean square of the heights
         * about their mean, which is never less than the levelling about the mean. NaN where the
         * footprint is not allowed.
         */
        public double estimate(int minX, int minZ) {
            int at = this.fits.corner(minX - Terrain.this.minX, minZ - Terrain.this.minZ);
            return at < 0 || Double.isNaN(this.fits.a[at]) ? Double.NaN
                    : this.fits.spread[at] * this.fits.width * this.fits.depth;
        }

        /** The footprint counted block by block, or empty where it is not allowed. */
        public Optional<Rect> fit(int minX, int minZ) {
            int at = this.fits.corner(minX - Terrain.this.minX, minZ - Terrain.this.minZ);
            if (at < 0 || Double.isNaN(this.fits.a[at])) {
                return Optional.empty();
            }
            int levelling = 0;
            long level = Math.round(this.fits.a[at]);
            int row = at / this.fits.cols;
            int col = at % this.fits.cols;
            for (int v = 0; v < this.fits.depth; v++) {
                for (int u = 0; u < this.fits.width; u++) {
                    levelling += (int) Math.abs(Terrain.this.ground[(row + v) * Terrain.this.width + col + u] - level);
                }
            }
            return Optional.of(new Rect(minX, minZ, this.fits.width, this.fits.depth, this.fits.a[at],
                    Math.hypot(this.fits.b[at], this.fits.c[at]), levelling, this.fits.trees[at]));
        }
    }

    /** Sites {@link #sites()} returns; a chooser asks {@link #allowed} of every position instead. */
    private static final int MAX_SITES = 8;

    /** Footprints fitted exactly after the cheap ranking. */
    private static final int SHORTLIST = 400;

    /** A cut this deep means something stands on the surface rather than being part of it. */
    private static final int STANDING_CUT = 2;

    /** How far around a hillside the land it must stand above is averaged. */
    private static final int LAND_RADIUS = 32;

    /** A hillside must fit a square this wide: room to dig a base's front into it. */
    private static final int HILLSIDE_SQUARE = 5;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int[] ground;
    private final int[] drops;
    private final Kind[] kinds;
    private final boolean[] cover;
    private final boolean[] valid;
    private final boolean[] standing;
    private final boolean[] flat;
    private final boolean[] lava;
    private final Fits fits;
    private final double treeCost;
    private final List<Site> sites;

    private Terrain(int minX, int minZ, int width, int[] ground, int[] drops, Kind[] kinds,
                    boolean[] cover, boolean[] valid, boolean[] standing, boolean[] flat,
                    boolean[] lava, Fits fits, double treeCost,
                    List<Site> sites) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.ground = ground;
        this.drops = drops;
        this.kinds = kinds;
        this.cover = cover;
        this.valid = valid;
        this.standing = standing;
        this.flat = flat;
        this.lava = lava;
        this.fits = fits;
        this.treeCost = treeCost;
        this.sites = sites;
    }

    public static Terrain analyse(GroundSample in, TerrainRules rules) {
        int w = in.width();
        int d = in.depth();
        int n = w * d;
        boolean[] known = new boolean[n];
        int[] surface = new int[n];
        for (int i = 0; i < n; i++) {
            surface[i] = in.surfaceAt(i);
            known[i] = surface[i] != GroundSample.UNKNOWN;
        }

        int[] opened = opening(surface, known, w, d);
        int[] ground = new int[n];
        boolean[] standing = new boolean[n];
        boolean[] cover = new boolean[n];
        boolean[] fluid = new boolean[n];
        boolean[] lava = new boolean[n];
        boolean[] usedBlock = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!known[i]) {
                ground[i] = GroundSample.UNKNOWN;
                continue;
            }
            boolean cut = surface[i] - opened[i] >= STANDING_CUT;
            ground[i] = cut ? opened[i] : surface[i];
            // Or the reader already looked under it: a trunk, a mushroom cap.
            standing[i] = cut || in.has(i, GroundSample.STANDING);
            cover[i] = standing[i] || in.has(i, GroundSample.CANOPY);
            fluid[i] = in.has(i, GroundSample.FLUID);
            lava[i] = in.has(i, GroundSample.LAVA);
            usedBlock[i] = in.has(i, GroundSample.USED);
        }
        boolean[] used = grow(usedBlock, w, d, rules.usedMargin());
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            valid[i] = known[i] && !fluid[i] && !used[i];
        }

        double[] smooth = smooth(ground, valid, w, d, rules.smoothRadius());
        boolean[] flat = new boolean[n];
        boolean[] flatOpen = new boolean[n];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                if (!valid[i]) {
                    continue;
                }
                double slope = slope(smooth, w, d, row, col, rules.smoothRadius());
                flat[i] = slope <= rules.maxSlope()
                        && Math.abs(ground[i] - smooth[i]) <= rules.maxRough();
                flatOpen[i] = flat[i] && !cover[i];
            }
        }
        boolean[] flatArea = openSquare(flat, w, d, rules.areaSize());
        boolean[] clearing = openSquare(flatOpen, w, d, rules.areaSize());
        int[] drops = drops(ground, known, w, d);
        boolean[] steep = steep(ground, known, fluid, w, d, rules);

        Kind[] kinds = new Kind[n];
        for (int i = 0; i < n; i++) {
            kinds[i] = !known[i] ? Kind.UNKNOWN
                    : fluid[i] ? Kind.FLUID
                    : used[i] ? Kind.USED
                    : drops[i] >= rules.cliffHeight() ? Kind.CLIFF
                    : steep[i] ? Kind.STEEP
                    : clearing[i] ? Kind.CLEARING
                    : flatArea[i] ? Kind.FLAT_AREA
                    : flat[i] ? Kind.FLAT
                    : Kind.UNEVEN;
        }
        Fits fits = Fits.of(ground, valid, standing, w, d, rules.footprint(), rules.footprint(),
                rules.maxTilt());
        List<Site> sites = sites(fits, ground, w, rules, in.minX(), in.minZ());
        return new Terrain(in.minX(), in.minZ(), w, ground, drops, kinds, cover, valid, standing, flat,
                lava, fits, rules.treeCost(), sites);
    }

    /**
     * How far past a column its judgement reads. A caller painting a box reads this much around
     * it, or the columns at its edge are judged on half their surroundings.
     */
    public static int reach(TerrainRules rules) {
        return Math.max(Math.max(rules.footprint(), 2 * rules.smoothRadius()),
                Math.max(rules.areaSize(), LAND_RADIUS + steepSpan(rules)));
    }

    /** The bare ground's top block, or {@link GroundSample#UNKNOWN}. */
    public int ground(int x, int z) {
        return this.ground[index(x, z)];
    }

    public Kind kind(int x, int z) {
        return this.kinds[index(x, z)];
    }

    /** How far the ground falls to its lowest neighbour, 0 if to none: at a cliff, its height. */
    public int drop(int x, int z) {
        return this.drops[index(x, z)];
    }

    /** Leaves overhead, or something standing on the ground here. */
    public boolean covered(int x, int z) {
        return this.cover[index(x, z)];
    }

    /** The best sites, cheapest first, none overlapping another. */
    /**
     * Every placement of a {@code width}×{@code depth} footprint, refused where any column is
     * unknown, fluid or used, or the ground's plane rises more than {@code maxTilt} a block —
     * {@link Double#POSITIVE_INFINITY} for a caller that levels whatever it gets.
     */
    public Rects rects(int width, int depth, double maxTilt) {
        if (width < 2 || depth < 2) {
            throw new IllegalArgumentException("a footprint is at least 2×2: " + width + "×" + depth);
        }
        return new Rects(Fits.of(this.ground, this.valid, this.standing, this.width,
                this.kinds.length / this.width, width, depth, maxTilt));
    }

    public List<Site> sites() {
        return this.sites;
    }

    /**
     * Whether a footprint of the rules' site size centred on {@code (x, z)} is allowed: every
     * column in it known, dry and unused, and its ground's plane within the tilt limit. False where
     * the footprint would leave the read.
     */
    public boolean allowed(int x, int z) {
        int at = this.fits.at(x - this.minX, z - this.minZ);
        return at >= 0 && !Double.isNaN(this.fits.a[at]);
    }

    /** The site centred on {@code (x, z)}, fitted exactly, or empty where it is not allowed. */
    public Optional<Site> site(int x, int z) {
        if (!allowed(x, z)) {
            return Optional.empty();
        }
        return Optional.of(fit(this.fits, this.fits.at(x - this.minX, z - this.minZ), this.ground,
                this.width, this.treeCost, this.minX, this.minZ));
    }

    /** Flat by the rules, whatever stands on it; area size and steepness aside. */
    public boolean flat(int x, int z) {
        return this.flat[index(x, z)];
    }

    /** Lava at the surface. Always {@link Kind#FLUID}. */
    public boolean lava(int x, int z) {
        return this.lava[index(x, z)];
    }

    public int minX() {
        return this.minX;
    }

    public int minZ() {
        return this.minZ;
    }

    public int width() {
        return this.width;
    }

    public int depth() {
        return this.kinds.length / this.width;
    }

    /** The side of the footprints {@link #allowed} judges: the rules' site size. */
    public int footprint() {
        return this.fits.width;
    }

    public int count(Kind kind) {
        int total = 0;
        for (Kind each : this.kinds) {
            if (each == kind) {
                total++;
            }
        }
        return total;
    }

    private int index(int x, int z) {
        int col = x - this.minX;
        int row = z - this.minZ;
        if (col < 0 || col >= this.width || row < 0 || row >= this.kinds.length / this.width) {
            throw new IndexOutOfBoundsException("(" + x + ", " + z + ") outside the terrain");
        }
        return row * this.width + col;
    }

    // ---- bare ground -------------------------------------------------------------------------

    /** 3×3 minimum then 3×3 maximum over known columns; unknown ones take no part. */
    private static int[] opening(int[] surface, boolean[] known, int w, int d) {
        int[] eroded = new int[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int low = Integer.MAX_VALUE;
                for (int r = Math.max(0, row - 1); r <= Math.min(d - 1, row + 1); r++) {
                    for (int c = Math.max(0, col - 1); c <= Math.min(w - 1, col + 1); c++) {
                        if (known[r * w + c]) {
                            low = Math.min(low, surface[r * w + c]);
                        }
                    }
                }
                eroded[row * w + col] = low;
            }
        }
        int[] opened = new int[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int high = Integer.MIN_VALUE;
                for (int r = Math.max(0, row - 1); r <= Math.min(d - 1, row + 1); r++) {
                    for (int c = Math.max(0, col - 1); c <= Math.min(w - 1, col + 1); c++) {
                        if (eroded[r * w + c] != Integer.MAX_VALUE) {
                            high = Math.max(high, eroded[r * w + c]);
                        }
                    }
                }
                opened[row * w + col] = high;
            }
        }
        return opened;
    }

    // ---- steep ground and cliffs ---------------------------------------------------------------

    private static final int[][] AXES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The largest fall from each column to a known neighbour along an axis. */
    private static int[] drops(int[] ground, boolean[] known, int w, int d) {
        int[] out = new int[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                if (!known[i]) {
                    continue;
                }
                for (int[] axis : AXES) {
                    int r = row + axis[1];
                    int c = col + axis[0];
                    if (r >= 0 && r < d && c >= 0 && c < w && known[r * w + c]) {
                        out[i] = Math.max(out[i], ground[i] - ground[r * w + c]);
                    }
                }
            }
        }
        return out;
    }

    /** The longest run over which climbing the steep height is still the steep angle or more. */
    private static int steepSpan(TerrainRules rules) {
        double run = rules.steepHeight() / Math.tan(Math.toRadians(rules.steepAngle()));
        return Math.max(1, (int) Math.floor(run + 1e-9));
    }

    /**
     * Hillsides: the ground climbs the steep height within the steep span, measured centred on the
     * column, the highest ground within the span stands above the land around, and a square of
     * such columns fits. Measured from the column out to a span away, the middle of a flank between
     * one and two steep heights tall climbed too little either way. A pit's wall climbs as far as a
     * hillside does, but its top is only the level of the land.
     */
    private static boolean[] steep(int[] ground, boolean[] known, boolean[] fluid, int w, int d,
                                   TerrainRules rules) {
        int span = steepSpan(rules);
        int ahead = span / 2;
        int behind = span - ahead;
        // Water counts: it is the low ground a plateau's flank rises from.
        double[] land = smooth(ground, known, w, d, LAND_RADIUS);
        int[] top = highest(ground, known, w, d, span);
        boolean[] hill = new boolean[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                if (!known[i] || fluid[i] || top[i] - land[i] < rules.steepAboveLand()) {
                    continue;
                }
                hill[i] = climbs(ground, known, w, d, row, col - behind, row, col + ahead, rules)
                        || climbs(ground, known, w, d, row - behind, col, row + ahead, col, rules);
            }
        }
        return openSquare(hill, w, d, HILLSIDE_SQUARE);
    }

    private static boolean climbs(int[] ground, boolean[] known, int w, int d, int r0, int c0,
                                  int r1, int c1, TerrainRules rules) {
        if (r0 < 0 || c0 < 0 || r1 >= d || c1 >= w) {
            return false;
        }
        int a = r0 * w + c0;
        int b = r1 * w + c1;
        return known[a] && known[b] && Math.abs(ground[b] - ground[a]) >= rules.steepHeight();
    }

    /** The highest known ground within {@code r} of each column, as a square. */
    private static int[] highest(int[] ground, boolean[] known, int w, int d, int r) {
        int[] across = new int[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int high = Integer.MIN_VALUE;
                for (int c = Math.max(0, col - r); c <= Math.min(w - 1, col + r); c++) {
                    if (known[row * w + c]) {
                        high = Math.max(high, ground[row * w + c]);
                    }
                }
                across[row * w + col] = high;
            }
        }
        int[] out = new int[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int high = Integer.MIN_VALUE;
                for (int rr = Math.max(0, row - r); rr <= Math.min(d - 1, row + r); rr++) {
                    high = Math.max(high, across[rr * w + col]);
                }
                out[row * w + col] = high;
            }
        }
        return out;
    }

    // ---- smoothing -----------------------------------------------------------------------------

    /** The mean of the valid ground within {@code r}; NaN where there is none. */
    private static double[] smooth(int[] ground, boolean[] valid, int w, int d, int r) {
        long[] sum = new long[(w + 1) * (d + 1)];
        long[] count = new long[(w + 1) * (d + 1)];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                int s = (row + 1) * (w + 1) + col + 1;
                int up = row * (w + 1) + col + 1;
                sum[s] = (valid[i] ? ground[i] : 0) + sum[s - 1] + sum[up] - sum[up - 1];
                count[s] = (valid[i] ? 1 : 0) + count[s - 1] + count[up] - count[up - 1];
            }
        }
        double[] out = new double[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int r0 = Math.max(0, row - r);
                int r1 = Math.min(d - 1, row + r);
                int c0 = Math.max(0, col - r);
                int c1 = Math.min(w - 1, col + r);
                long n = window(count, w, r0, c0, r1, c1);
                out[row * w + col] = n == 0 ? Double.NaN
                        : (double) window(sum, w, r0, c0, r1, c1) / n;
            }
        }
        return out;
    }

    /**
     * Rise per block of the smoothed ground, measured across {@code r} either side — the scale
     * flatness is judged at. Infinite where either end has nothing to measure.
     */
    private static double slope(double[] smooth, int w, int d, int row, int col, int r) {
        int c0 = Math.max(0, col - r);
        int c1 = Math.min(w - 1, col + r);
        int r0 = Math.max(0, row - r);
        int r1 = Math.min(d - 1, row + r);
        double sx = c1 > c0 ? (smooth[row * w + c1] - smooth[row * w + c0]) / (c1 - c0) : 0;
        double sz = r1 > r0 ? (smooth[r1 * w + col] - smooth[r0 * w + col]) / (r1 - r0) : 0;
        double slope = Math.hypot(sx, sz);
        return Double.isNaN(slope) ? Double.POSITIVE_INFINITY : slope;
    }

    // ---- areas ---------------------------------------------------------------------------------

    /** Every cell within {@code margin} of a marked one, the marked ones included. */
    private static boolean[] grow(boolean[] mask, int w, int d, int margin) {
        long[] sat = sat(mask, w, d);
        boolean[] out = new boolean[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                out[row * w + col] = window(sat, w, Math.max(0, row - margin),
                        Math.max(0, col - margin), Math.min(d - 1, row + margin),
                        Math.min(w - 1, col + margin)) > 0;
            }
        }
        return out;
    }

    /** The union of every {@code k}×{@code k} square lying wholly inside {@code mask}. */
    private static boolean[] openSquare(boolean[] mask, int w, int d, int k) {
        int h = k / 2;
        long[] sat = sat(mask, w, d);
        boolean[] fits = new boolean[w * d];
        for (int row = h; row < d - h; row++) {
            for (int col = h; col < w - h; col++) {
                fits[row * w + col] =
                        window(sat, w, row - h, col - h, row + h, col + h) == (long) k * k;
            }
        }
        return grow(fits, w, d, h);
    }

    // ---- sites ---------------------------------------------------------------------------------

    /**
     * A plane {@code a + b·u + c·v} fitted to the ground under every footprint, by its top-left
     * cell, {@code u} and {@code v} measured from its centre; {@code a} is NaN where the footprint
     * is refused. On a rectangle the two axes are orthogonal, so the fit is three window sums and
     * costs the same at every position.
     */
    private static final class Fits {
        final int width;
        final int depth;
        final int cols;
        final int rows;
        final double[] a;
        final double[] b;
        final double[] c;
        /** The root mean square off the plane. */
        final double[] rms;
        /** The root mean square off the mean: what levelling, not tilting, has to move. */
        final double[] spread;
        final int[] trees;

        private Fits(int width, int depth, int cols, int rows) {
            this.width = width;
            this.depth = depth;
            this.cols = cols;
            this.rows = rows;
            int n = cols * rows;
            this.a = new double[n];
            this.b = new double[n];
            this.c = new double[n];
            this.rms = new double[n];
            this.spread = new double[n];
            this.trees = new int[n];
        }

        /** The odd square footprint centred on this cell, or -1 where it would leave the read. */
        int at(int col, int row) {
            return corner(col - this.width / 2, row - this.depth / 2);
        }

        /** The footprint with its top-left cell here, or -1 where it would leave the read. */
        int corner(int c0, int r0) {
            return c0 < 0 || r0 < 0 || c0 >= this.cols || r0 >= this.rows ? -1 : r0 * this.cols + c0;
        }

        static Fits of(int[] ground, boolean[] valid, boolean[] standing, int w, int d, int fw, int fd,
                       double maxTilt) {
            Fits out = new Fits(fw, fd, Math.max(0, w - fw + 1), Math.max(0, d - fd + 1));
            if (out.cols == 0 || out.rows == 0) {
                return out;
            }
            int stride = w + 1;
            double[] sG = new double[stride * (d + 1)];
            double[] sXG = new double[stride * (d + 1)];
            double[] sZG = new double[stride * (d + 1)];
            double[] sGG = new double[stride * (d + 1)];
            long[] sBad = new long[stride * (d + 1)];
            long[] sTrees = new long[stride * (d + 1)];
            for (int row = 0; row < d; row++) {
                for (int col = 0; col < w; col++) {
                    int i = row * w + col;
                    double g = valid[i] ? ground[i] : 0;
                    // One cell per trunk: a 2×2 trunk is one tree, not four.
                    boolean trunk = standing[i] && (col == 0 || !standing[i - 1])
                            && (row == 0 || !standing[i - w]);
                    int s = (row + 1) * stride + col + 1;
                    int up = row * stride + col + 1;
                    sG[s] = g + sG[s - 1] + sG[up] - sG[up - 1];
                    sXG[s] = col * g + sXG[s - 1] + sXG[up] - sXG[up - 1];
                    sZG[s] = row * g + sZG[s - 1] + sZG[up] - sZG[up - 1];
                    sGG[s] = g * g + sGG[s - 1] + sGG[up] - sGG[up - 1];
                    sBad[s] = (valid[i] ? 0 : 1) + sBad[s - 1] + sBad[up] - sBad[up - 1];
                    sTrees[s] = (trunk ? 1 : 0) + sTrees[s - 1] + sTrees[up] - sTrees[up - 1];
                }
            }
            double n = (double) fw * fd;
            // Σ(u − ū)² over the footprint, and the same along v.
            double u2 = fd * fw * ((double) fw * fw - 1) / 12.0;
            double v2 = fw * fd * ((double) fd * fd - 1) / 12.0;
            for (int row = 0; row < out.rows; row++) {
                for (int col = 0; col < out.cols; col++) {
                    int at = row * out.cols + col;
                    int r1 = row + fd - 1;
                    int c1 = col + fw - 1;
                    out.a[at] = Double.NaN;
                    if (window(sBad, w, row, col, r1, c1) > 0) {
                        continue;
                    }
                    double sumG = window(sG, w, row, col, r1, c1);
                    double cx = col + (fw - 1) / 2.0;
                    double cz = row + (fd - 1) / 2.0;
                    double a = sumG / n;
                    double b = (window(sXG, w, row, col, r1, c1) - cx * sumG) / u2;
                    double c = (window(sZG, w, row, col, r1, c1) - cz * sumG) / v2;
                    if (Math.hypot(b, c) > maxTilt) {
                        continue;
                    }
                    double offMean = window(sGG, w, row, col, r1, c1) - n * a * a;
                    out.a[at] = a;
                    out.b[at] = b;
                    out.c[at] = c;
                    out.rms[at] = Math.sqrt(Math.max(0, offMean - b * b * u2 - c * c * v2) / n);
                    out.spread[at] = Math.sqrt(Math.max(0, offMean) / n);
                    out.trees[at] = (int) window(sTrees, w, row, col, r1, c1);
                }
            }
            return out;
        }
    }

    /** The footprint at {@code at} in {@code fits}, its preparation counted block by block. */
    private static Site fit(Fits fits, int at, int[] ground, int w, double treeCost, int minX,
                            int minZ) {
        int f = fits.width;
        int row = at / fits.cols;
        int col = at % fits.cols;
        int preparation = 0;
        int levelling = 0;
        long level = Math.round(fits.a[at]);
        for (int v = 0; v < f; v++) {
            for (int u = 0; u < f; u++) {
                int g = ground[(row + v) * w + col + u];
                double plane = fits.a[at] + fits.b[at] * (u - (f - 1) / 2.0)
                        + fits.c[at] * (v - (f - 1) / 2.0);
                preparation += (int) Math.abs(g - Math.round(plane));
                levelling += (int) Math.abs(g - level);
            }
        }
        int trees = fits.trees[at];
        return new Site(minX + col + f / 2, minZ + row + f / 2, f, fits.a[at],
                Math.hypot(fits.b[at], fits.c[at]), preparation, levelling, trees,
                preparation + treeCost * trees);
    }

    /**
     * The cheapest sites, none overlapping another: every allowed footprint ranked by a cheap
     * estimate, the best {@link #SHORTLIST} fitted exactly.
     */
    private static List<Site> sites(Fits fits, int[] ground, int w, TerrainRules rules, int minX,
                                    int minZ) {
        int f = fits.width;
        double n = (double) f * f;
        List<Integer> candidates = new ArrayList<>();
        double[] rank = new double[fits.a.length];
        for (int at = 0; at < fits.a.length; at++) {
            if (!Double.isNaN(fits.a[at])) {
                // n·RMS is not the preparation, only a cheap order to shortlist by.
                rank[at] = fits.rms[at] * n + rules.treeCost() * fits.trees[at];
                candidates.add(at);
            }
        }
        candidates.sort(Comparator.comparingDouble(at -> rank[at]));
        List<Site> fitted = new ArrayList<>();
        for (int at : candidates.subList(0, Math.min(SHORTLIST, candidates.size()))) {
            fitted.add(fit(fits, at, ground, w, rules.treeCost(), minX, minZ));
        }
        fitted.sort(Comparator.comparingDouble(Site::cost));
        List<Site> best = new ArrayList<>();
        for (Site site : fitted) {
            boolean overlaps = false;
            for (Site other : best) {
                if (Math.abs(other.x() - site.x()) < f && Math.abs(other.z() - site.z()) < f) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                best.add(site);
                if (best.size() == MAX_SITES) {
                    break;
                }
            }
        }
        return List.copyOf(best);
    }

    // ---- summed-area tables --------------------------------------------------------------------

    private static long[] sat(boolean[] mask, int w, int d) {
        long[] s = new long[(w + 1) * (d + 1)];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int at = (row + 1) * (w + 1) + col + 1;
                int up = row * (w + 1) + col + 1;
                s[at] = (mask[row * w + col] ? 1 : 0) + s[at - 1] + s[up] - s[up - 1];
            }
        }
        return s;
    }

    /** Sum over rows {@code r0..r1}, columns {@code c0..c1}, inclusive. */
    private static long window(long[] s, int w, int r0, int c0, int r1, int c1) {
        int stride = w + 1;
        return s[(r1 + 1) * stride + c1 + 1] - s[r0 * stride + c1 + 1]
                - s[(r1 + 1) * stride + c0] + s[r0 * stride + c0];
    }

    private static double window(double[] s, int w, int r0, int c0, int r1, int c1) {
        int stride = w + 1;
        return s[(r1 + 1) * stride + c1 + 1] - s[r0 * stride + c1 + 1]
                - s[(r1 + 1) * stride + c0] + s[r0 * stride + c0];
    }
}
