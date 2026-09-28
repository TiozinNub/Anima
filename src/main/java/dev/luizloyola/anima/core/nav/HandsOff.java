package dev.luizloyola.anima.core.nav;

import java.util.List;

/**
 * Where a route may pass but may not lay or cut (docs/superpowers/specs/2026-09-28-bridging-design.md,
 * safety rule 6): the consumer's sites, structures, roads and fields, as boxes of whole columns. The
 * other half of the fence from {@link NavDomain}, which says where a body may stand at all.
 *
 * <p>A snapshot like the domain: built on the server thread, immutable for the worker.
 */
public final class HandsOff {

    /** Nothing fenced — every request that does not say otherwise. */
    public static final HandsOff NONE = new HandsOff(new int[0]);

    /** {@code x1, z1, x2, z2} per box, corners sorted. */
    private final int[] boxes;

    private HandsOff(int[] boxes) {
        this.boxes = boxes;
    }

    /** These boxes of columns, each {@code {x1, z1, x2, z2}} in any corner order. */
    public static HandsOff columns(List<int[]> boxes) {
        if (boxes.isEmpty()) {
            return NONE;
        }
        int[] flat = new int[boxes.size() * 4];
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            flat[i * 4] = Math.min(b[0], b[2]);
            flat[i * 4 + 1] = Math.min(b[1], b[3]);
            flat[i * 4 + 2] = Math.max(b[0], b[2]);
            flat[i * 4 + 3] = Math.max(b[1], b[3]);
        }
        return new HandsOff(flat);
    }

    /** Whether nothing may be laid or cut in this column. */
    public boolean contains(int x, int z) {
        for (int i = 0; i < this.boxes.length; i += 4) {
            if (x >= this.boxes[i] && z >= this.boxes[i + 1] && x <= this.boxes[i + 2]
                    && z <= this.boxes[i + 3]) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return this.boxes.length == 0;
    }
}
