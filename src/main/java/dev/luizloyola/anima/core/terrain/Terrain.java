package dev.luizloyola.anima.core.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        /** Rising at the steep angle or more across four blocks. */
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

    /** Sites returned; more is noise in a debug view and a waste for a chooser. */
    private static final int MAX_SITES = 8;

    /** Footprints fitted exactly after the cheap ranking. */
    private static final int SHORTLIST = 400;

    /** A cut this deep means something stands on the surface rather than being part of it. */
    private static final int STANDING_CUT = 2;

    /**
     * Steepness is measured across this many blocks. Across four, the smallest rise steep at 45° is
     * four blocks, the cliff height, so no hole, bump or ledge of 1–3 is steep; across two, every
     * two-block hole in a meadow was.
     */
    private static final int STEEP_SPAN = 4;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int[] ground;
    private final int[] drops;
    private final Kind[] kinds;
    private final boolean[] cover;
    private final List<Site> sites;

    private Terrain(int minX, int minZ, int width, int[] ground, int[] drops, Kind[] kinds,
                    boolean[] cover, List<Site> sites) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.ground = ground;
        this.drops = drops;
        this.kinds = kinds;
        this.cover = cover;
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
        boolean[] steep = steep(ground, known, w, d, Math.tan(Math.toRadians(rules.steepAngle())));

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
        List<Site> sites = sites(ground, valid, standing, w, d, rules, in.minX(), in.minZ());
        return new Terrain(in.minX(), in.minZ(), w, ground, drops, kinds, cover, sites);
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
    public List<Site> sites() {
        return this.sites;
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

    /** Where the ground rises by {@code rise} per block or more, up or down, across the span. */
    private static boolean[] steep(int[] ground, boolean[] known, int w, int d, double rise) {
        boolean[] out = new boolean[w * d];
        for (int row = 0; row < d; row++) {
            for (int col = 0; col < w; col++) {
                int i = row * w + col;
                if (!known[i]) {
                    continue;
                }
                for (int[] axis : AXES) {
                    int r = row + axis[1] * STEEP_SPAN;
                    int c = col + axis[0] * STEEP_SPAN;
                    if (r >= 0 && r < d && c >= 0 && c < w && known[r * w + c]
                            && Math.abs(ground[i] - ground[r * w + c]) >= rise * STEEP_SPAN - 1e-9) {
                        out[i] = true;
                        break;
                    }
                }
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

    private record Candidate(int row, int col, double a, double b, double c, double tilt,
                             int trees, double rank) {
    }

    /**
     * Fits a plane {@code a + b·u + c·v} to the ground under every footprint, {@code u} and
     * {@code v} measured from its centre. On a square the two axes are orthogonal, so the fit is
     * three window sums and costs the same at every position.
     */
    private static List<Site> sites(int[] ground, boolean[] valid, boolean[] standing, int w,
                                    int d, TerrainRules rules, int minX, int minZ) {
        int f = rules.footprint();
        if (w < f || d < f) {
            return List.of();
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
        double n = (double) f * f;
        double u2 = f * ((double) f * f - 1) / 12.0 * f;
        List<Candidate> candidates = new ArrayList<>();
        for (int row = 0; row + f <= d; row++) {
            for (int col = 0; col + f <= w; col++) {
                int r1 = row + f - 1;
                int c1 = col + f - 1;
                if (window(sBad, w, row, col, r1, c1) > 0) {
                    continue;
                }
                double sumG = window(sG, w, row, col, r1, c1);
                double cx = col + (f - 1) / 2.0;
                double cz = row + (f - 1) / 2.0;
                double a = sumG / n;
                double b = (window(sXG, w, row, col, r1, c1) - cx * sumG) / u2;
                double c = (window(sZG, w, row, col, r1, c1) - cz * sumG) / u2;
                double tilt = Math.hypot(b, c);
                if (tilt > rules.maxTilt()) {
                    continue;
                }
                double rss = Math.max(0,
                        window(sGG, w, row, col, r1, c1) - n * a * a - (b * b + c * c) * u2);
                int trees = (int) window(sTrees, w, row, col, r1, c1);
                // n·RMS is not the preparation, only a cheap order to shortlist by.
                candidates.add(new Candidate(row, col, a, b, c, tilt, trees,
                        Math.sqrt(rss / n) * n + rules.treeCost() * trees));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::rank));
        List<Site> fitted = new ArrayList<>();
        List<Candidate> kept = new ArrayList<>();
        for (Candidate cand : candidates.subList(0, Math.min(SHORTLIST, candidates.size()))) {
            int preparation = 0;
            int levelling = 0;
            long level = Math.round(cand.a());
            for (int v = 0; v < f; v++) {
                for (int u = 0; u < f; u++) {
                    int g = ground[(cand.row() + v) * w + cand.col() + u];
                    double plane = cand.a() + cand.b() * (u - (f - 1) / 2.0)
                            + cand.c() * (v - (f - 1) / 2.0);
                    preparation += (int) Math.abs(g - Math.round(plane));
                    levelling += (int) Math.abs(g - level);
                }
            }
            fitted.add(new Site(minX + cand.col() + f / 2, minZ + cand.row() + f / 2, f, cand.a(),
                    cand.tilt(), preparation, levelling, cand.trees(),
                    preparation + rules.treeCost() * cand.trees()));
            kept.add(cand);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < fitted.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> fitted.get(i).cost()));
        List<Site> best = new ArrayList<>();
        List<Candidate> taken = new ArrayList<>();
        for (int i : order) {
            Candidate cand = kept.get(i);
            boolean overlaps = false;
            for (Candidate other : taken) {
                if (Math.abs(other.row() - cand.row()) < f && Math.abs(other.col() - cand.col()) < f) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                taken.add(cand);
                best.add(fitted.get(i));
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
