package dev.luizloyola.anima.core.terrain;

import java.util.Arrays;

/**
 * The ground over a box of columns as it was read, before anything is inferred: the top of
 * whatever stops a body (leaves excluded, and the ground under a tree or a huge mushroom), and four
 * facts about each column.
 *
 * <p>Filled by the compat reader from loaded chunks only; a column it could not read stays
 * {@link #UNKNOWN}, which every rule downstream treats as "I could not see there", never as open
 * ground.
 */
public final class GroundSample {

    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** Leaves stand over this column. */
    public static final int CANOPY = 1;
    /** Water or lava at the surface, open or under leaves. */
    public static final int FLUID = 2;
    /** The surface block, or the one standing on it, says somebody uses this ground. */
    public static final int USED = 4;
    /** A trunk or a huge mushroom stands here; the surface given is the ground under it. */
    public static final int STANDING = 8;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] surface;
    private final byte[] flags;

    public GroundSample(int minX, int minZ, int width, int depth) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("empty sample: " + width + "x" + depth);
        }
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.surface = new int[width * depth];
        this.flags = new byte[width * depth];
        Arrays.fill(this.surface, UNKNOWN);
    }

    /** Records one column, by world coordinates. */
    public void set(int x, int z, int surfaceY, int columnFlags) {
        int i = index(x, z);
        this.surface[i] = surfaceY;
        this.flags[i] = (byte) columnFlags;
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
        return this.depth;
    }

    int surfaceAt(int i) {
        return this.surface[i];
    }

    boolean has(int i, int flag) {
        return (this.flags[i] & flag) != 0;
    }

    int index(int x, int z) {
        int col = x - this.minX;
        int row = z - this.minZ;
        if (col < 0 || col >= this.width || row < 0 || row >= this.depth) {
            throw new IndexOutOfBoundsException("(" + x + ", " + z + ") outside the sample");
        }
        return row * this.width + col;
    }
}
