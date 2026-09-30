package dev.luizloyola.anima.core.terrain;

import java.util.HashMap;
import java.util.Map;

/**
 * What lies around each place in a {@link Terrain}'s read, for a caller that judges a place by its
 * surroundings: how far a square's edge is from water or lava of some size, and how much flat
 * ground lies near it. Each layer is worked out over the whole read on first use and kept, so
 * asking of every allowed footprint costs a lookup apiece.
 *
 * <p>Distances run from the square's closest column, not its centre: a footprint never overlaps
 * water, so from its centre the nearest water is always more than half a footprint away. They are
 * Euclidean, between column centres, so water beside the square's edge is 1 away.
 *
 * <p>A body is what the read holds of it: a lake cut by the read's edge counts only its part inside.
 */
public final class Landscape {

    public enum Fluid {
        /** Water, open or frozen. */
        WATER,
        LAVA
    }

    private record Layer(Fluid fluid, int minCells, int size) {
    }

    private final Terrain terrain;
    private final int w;
    private final int d;
    private final Map<Layer, float[]> distances = new HashMap<>();
    private long[] flatSums;

    public Landscape(Terrain terrain) {
        this.terrain = terrain;
        this.w = terrain.width();
        this.d = terrain.depth();
    }

    public Terrain terrain() {
        return this.terrain;
    }

    /**
     * How far the closest column of the {@code size}×{@code size} square centred on {@code (x, z)}
     * lies from the nearest column of a body of {@code fluid} at least {@code minCells} columns big,
     * four-connected. Infinite when the read holds no such body; 0 where the square covers one.
     */
    public double toFluid(Fluid fluid, int minCells, int size, int x, int z) {
        float[] layer = this.distances.computeIfAbsent(new Layer(fluid, minCells, size),
                key -> squareMinimum(distanceTo(bodies(key.fluid(), key.minCells())), key.size()));
        return layer[index(x, z)];
    }

    /**
     * Flat columns within the square of half-width {@code radius} centred on {@code (x, z)},
     * outside the {@code size}×{@code size} square centred there: the ground a footprint has
     * beside it.
     */
    public int flatAround(int x, int z, int radius, int size) {
        if (this.flatSums == null) {
            this.flatSums = flatSums();
        }
        int col = x - this.terrain.minX();
        int row = z - this.terrain.minZ();
        return count(row, col, radius) - count(row, col, size / 2);
    }

    private int index(int x, int z) {
        int col = x - this.terrain.minX();
        int row = z - this.terrain.minZ();
        if (col < 0 || col >= this.w || row < 0 || row >= this.d) {
            throw new IndexOutOfBoundsException("(" + x + ", " + z + ") outside the landscape");
        }
        return row * this.w + col;
    }

    // ---- bodies --------------------------------------------------------------------------------

    /** The columns of every body of {@code fluid} at least {@code minCells} columns big. */
    private boolean[] bodies(Fluid fluid, int minCells) {
        int n = this.w * this.d;
        boolean[] of = new boolean[n];
        int x0 = this.terrain.minX();
        int z0 = this.terrain.minZ();
        for (int row = 0; row < this.d; row++) {
            for (int col = 0; col < this.w; col++) {
                int x = x0 + col;
                int z = z0 + row;
                of[row * this.w + col] = this.terrain.kind(x, z) == Terrain.Kind.FLUID
                        && this.terrain.lava(x, z) == (fluid == Fluid.LAVA);
            }
        }
        boolean[] kept = new boolean[n];
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        for (int start = 0; start < n; start++) {
            if (!of[start] || seen[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int i = queue[head++];
                int row = i / this.w;
                int col = i % this.w;
                if (col > 0 && of[i - 1] && !seen[i - 1]) {
                    seen[i - 1] = true;
                    queue[tail++] = i - 1;
                }
                if (col < this.w - 1 && of[i + 1] && !seen[i + 1]) {
                    seen[i + 1] = true;
                    queue[tail++] = i + 1;
                }
                if (row > 0 && of[i - this.w] && !seen[i - this.w]) {
                    seen[i - this.w] = true;
                    queue[tail++] = i - this.w;
                }
                if (row < this.d - 1 && of[i + this.w] && !seen[i + this.w]) {
                    seen[i + this.w] = true;
                    queue[tail++] = i + this.w;
                }
            }
            if (tail >= minCells) {
                for (int k = 0; k < tail; k++) {
                    kept[queue[k]] = true;
                }
            }
        }
        return kept;
    }

    // ---- distance ------------------------------------------------------------------------------

    private static final double FAR = 1e20;

    /**
     * The exact Euclidean distance from each column to the nearest marked one, infinite with none:
     * Felzenszwalb and Huttenlocher's two passes of the lower envelope of parabolas, rows then
     * columns.
     */
    private float[] distanceTo(boolean[] mask) {
        int n = this.w * this.d;
        double[] sq = new double[n];
        for (int i = 0; i < n; i++) {
            sq[i] = mask[i] ? 0 : FAR;
        }
        int longest = Math.max(this.w, this.d);
        double[] f = new double[longest];
        double[] out = new double[longest];
        int[] v = new int[longest];
        double[] z = new double[longest + 1];
        for (int row = 0; row < this.d; row++) {
            for (int col = 0; col < this.w; col++) {
                f[col] = sq[row * this.w + col];
            }
            envelope(f, this.w, out, v, z);
            for (int col = 0; col < this.w; col++) {
                sq[row * this.w + col] = out[col];
            }
        }
        for (int col = 0; col < this.w; col++) {
            for (int row = 0; row < this.d; row++) {
                f[row] = sq[row * this.w + col];
            }
            envelope(f, this.d, out, v, z);
            for (int row = 0; row < this.d; row++) {
                sq[row * this.w + col] = out[row];
            }
        }
        float[] dist = new float[n];
        for (int i = 0; i < n; i++) {
            dist[i] = sq[i] >= FAR ? Float.POSITIVE_INFINITY : (float) Math.sqrt(sq[i]);
        }
        return dist;
    }

    /** One pass: {@code out[q] = min over p of (q − p)² + f[p]}. */
    private static void envelope(double[] f, int n, double[] out, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = meet(f, q, v[k]);
            // z[0] is minus infinity, so this stops at k = 0.
            while (s <= z[k]) {
                k--;
                s = meet(f, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            double dq = q - v[k];
            out[q] = dq * dq + f[v[k]];
        }
    }

    /** Where the parabolas rooted at {@code q} and {@code p} cross. */
    private static double meet(double[] f, int q, int p) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }

    /** The minimum over the {@code size}×{@code size} square centred on each column, clipped to the read. */
    private float[] squareMinimum(float[] in, int size) {
        int h = size / 2;
        float[] across = new float[in.length];
        for (int row = 0; row < this.d; row++) {
            slidingMinimum(in, row * this.w, 1, this.w, h, across);
        }
        float[] out = new float[in.length];
        for (int col = 0; col < this.w; col++) {
            slidingMinimum(across, col, this.w, this.d, h, out);
        }
        return out;
    }

    /** A line's minimum within {@code h} either side of each cell, by a monotone deque. */
    private static void slidingMinimum(float[] in, int start, int step, int n, int h, float[] out) {
        int[] deque = new int[n];
        int head = 0;
        int tail = 0;
        int next = 0;
        for (int i = 0; i < n; i++) {
            while (next < n && next <= i + h) {
                float value = in[start + next * step];
                while (tail > head && in[start + deque[tail - 1] * step] >= value) {
                    tail--;
                }
                deque[tail++] = next++;
            }
            while (deque[head] < i - h) {
                head++;
            }
            out[start + i * step] = in[start + deque[head] * step];
        }
    }

    // ---- flat ground ---------------------------------------------------------------------------

    private long[] flatSums() {
        int stride = this.w + 1;
        long[] s = new long[stride * (this.d + 1)];
        int x0 = this.terrain.minX();
        int z0 = this.terrain.minZ();
        for (int row = 0; row < this.d; row++) {
            for (int col = 0; col < this.w; col++) {
                int at = (row + 1) * stride + col + 1;
                int up = row * stride + col + 1;
                s[at] = (this.terrain.flat(x0 + col, z0 + row) ? 1 : 0) + s[at - 1] + s[up] - s[up - 1];
            }
        }
        return s;
    }

    /** Flat columns in the square of half-width {@code r} around a cell, clipped to the read. */
    private int count(int row, int col, int r) {
        int r0 = Math.max(0, row - r);
        int c0 = Math.max(0, col - r);
        int r1 = Math.min(this.d - 1, row + r);
        int c1 = Math.min(this.w - 1, col + r);
        if (r0 > r1 || c0 > c1) {
            return 0;
        }
        int stride = this.w + 1;
        return (int) (this.flatSums[(r1 + 1) * stride + c1 + 1] - this.flatSums[r0 * stride + c1 + 1]
                - this.flatSums[(r1 + 1) * stride + c0] + this.flatSums[r0 * stride + c0]);
    }
}
