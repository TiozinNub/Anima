package dev.luizloyola.anima.core.nav;

import java.util.List;

/**
 * Where a route may pass but may not lay or cut (docs/superpowers/specs/2026-09-28-bridging-design.md,
 * safety rule 6): the consumer's sites, structures, roads and fields, as boxes of whole columns. The
 * other half of the fence from {@link NavDomain}, which says where a body may stand at all.
 *
 * <p>Some ground is fenced against cutting only ({@link #columns(List, List)}): a deck or a pillar
 * may cross it, a scale or a carve may not.
 *
 * <p>A snapshot like the domain: built on the server thread, immutable for the worker.
 */
public final class HandsOff {

    /** Nothing fenced — every request that does not say otherwise. */
    public static final HandsOff NONE = new HandsOff(new int[0], new int[0]);

    /** {@code x1, z1, x2, z2} per box, corners sorted: nothing laid or cut. */
    private final int[] boxes;
    /** The same, for boxes where only cutting is fenced. */
    private final int[] uncut;

    private HandsOff(int[] boxes, int[] uncut) {
        this.boxes = boxes;
        this.uncut = uncut;
    }

    /** These boxes of columns, each {@code {x1, z1, x2, z2}} in any corner order. */
    public static HandsOff columns(List<int[]> boxes) {
        return columns(boxes, List.of());
    }

    /**
     * {@code boxes} fenced against laying and cutting both, {@code uncut} against cutting only;
     * each {@code {x1, z1, x2, z2}} in any corner order.
     */
    public static HandsOff columns(List<int[]> boxes, List<int[]> uncut) {
        if (boxes.isEmpty() && uncut.isEmpty()) {
            return NONE;
        }
        return new HandsOff(flatten(boxes), flatten(uncut));
    }

    private static int[] flatten(List<int[]> boxes) {
        int[] flat = new int[boxes.size() * 4];
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            flat[i * 4] = Math.min(b[0], b[2]);
            flat[i * 4 + 1] = Math.min(b[1], b[3]);
            flat[i * 4 + 2] = Math.max(b[0], b[2]);
            flat[i * 4 + 3] = Math.max(b[1], b[3]);
        }
        return flat;
    }

    /** Whether nothing may be laid or cut in this column. */
    public boolean contains(int x, int z) {
        return within(this.boxes, x, z);
    }

    /** Whether nothing may be cut in this column: every fenced one, and those fenced against cutting. */
    public boolean barsCut(int x, int z) {
        return within(this.boxes, x, z) || within(this.uncut, x, z);
    }

    private static boolean within(int[] boxes, int x, int z) {
        for (int i = 0; i < boxes.length; i += 4) {
            if (x >= boxes[i] && z >= boxes[i + 1] && x <= boxes[i + 2] && z <= boxes[i + 3]) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return this.boxes.length == 0 && this.uncut.length == 0;
    }
}
