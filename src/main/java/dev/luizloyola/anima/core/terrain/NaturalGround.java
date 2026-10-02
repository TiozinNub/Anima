package dev.luizloyola.anima.core.terrain;

import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * The natural ground over a box of columns: the top natural block of each, read through whatever
 * stands on it, and what was read through. Where {@link GroundSample} answers "where does a body
 * stop", this answers "where is the ground nobody put there" — what earthwork cuts and fills.
 *
 * <p>A column the reader could not see, or whose natural ground lies deeper than it looked, stays
 * {@link #UNKNOWN}.
 */
public final class NaturalGround {

    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** A trunk or a huge mushroom stands on this column. */
    public static final int TREE = 1;
    /** Something neither natural nor growing stands on this column, or replaced its top. */
    public static final int BUILT = 2;
    /** The column ends in water or lava; its ground is the fluid's top, not natural ground. */
    public static final int FLUID = 4;

    /** What one block of a column is, to this reader. */
    public enum Cell {
        /** Air, leaves, plants, snow layers: read through and forgotten. */
        OPEN,
        /** In {@code #anima:not_ground}. */
        TREE,
        /** Anything that is not natural, open, a tree or a fluid. */
        BUILT,
        /** In {@code #anima:natural_ground}. */
        NATURAL,
        FLUID
    }

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] ground;
    private final byte[] flags;

    public NaturalGround(int minX, int minZ, int width, int depth) {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("empty box: " + width + "x" + depth);
        }
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.ground = new int[width * depth];
        this.flags = new byte[width * depth];
        Arrays.fill(this.ground, UNKNOWN);
    }

    /**
     * Walks one column down from {@code top} and records it: the first natural block is the ground;
     * a fluid ends the walk at its top. Nothing natural within {@code maxDepth} leaves it unknown,
     * with the flags of what was read through.
     */
    public void walk(int x, int z, int top, int maxDepth, IntFunction<Cell> cellAt) {
        int columnFlags = 0;
        for (int y = top; y > top - maxDepth; y--) {
            switch (cellAt.apply(y)) {
                case OPEN -> { }
                case TREE -> columnFlags |= TREE;
                case BUILT -> columnFlags |= BUILT;
                case FLUID -> {
                    set(x, z, y, columnFlags | FLUID);
                    return;
                }
                case NATURAL -> {
                    set(x, z, y, columnFlags);
                    return;
                }
            }
        }
        set(x, z, UNKNOWN, columnFlags);
    }

    /**
     * The top natural block's Y walking one column down from {@code top}, or a fluid's top where
     * the walk ends in one — {@link #UNKNOWN} when neither turns up within {@code maxDepth}.
     */
    public static int groundOf(int top, int maxDepth, IntFunction<Cell> cellAt) {
        for (int y = top; y > top - maxDepth; y--) {
            Cell cell = cellAt.apply(y);
            if (cell == Cell.NATURAL || cell == Cell.FLUID) {
                return y;
            }
        }
        return UNKNOWN;
    }

    /** Records one column, by world coordinates. */
    public void set(int x, int z, int groundY, int columnFlags) {
        int i = index(x, z);
        this.ground[i] = groundY;
        this.flags[i] = (byte) columnFlags;
    }

    /** The top natural block's Y, or {@link #UNKNOWN}. */
    public int groundAt(int x, int z) {
        return this.ground[index(x, z)];
    }

    public boolean has(int x, int z, int flag) {
        return (this.flags[index(x, z)] & flag) != 0;
    }

    public boolean contains(int x, int z) {
        int col = x - this.minX;
        int row = z - this.minZ;
        return col >= 0 && col < this.width && row >= 0 && row < this.depth;
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

    private int index(int x, int z) {
        if (!contains(x, z)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + z + ") outside the box");
        }
        return (z - this.minZ) * this.width + (x - this.minX);
    }
}
