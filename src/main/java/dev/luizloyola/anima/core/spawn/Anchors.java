package dev.luizloyola.anima.core.spawn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The bodies in one level that mobs spawn round as round a player: where they stand, and the
 * questions vanilla's spawner asks of a player, asked of them. Players are not in it; the
 * spawner still asks after those itself.
 *
 * <p>Immutable. Built once a tick per level and read by every spawn attempt and despawn check.
 */
public final class Anchors {

    /**
     * Chunks out from a body's own that it spawns in: the 5x5 its simulation ticket entity-ticks.
     * The block-ticking ring beyond would spawn mobs that stand frozen.
     */
    public static final int SPAWN_RADIUS_CHUNKS = 2;

    /** Vanilla's mob cap area: a cap of {@code max} belongs to 17x17 chunks. */
    private static final int VANILLA_CAP_CHUNKS = 17 * 17;

    private static final int SPAWN_CHUNKS = (2 * SPAWN_RADIUS_CHUNKS + 1) * (2 * SPAWN_RADIUS_CHUNKS + 1);

    public static final Anchors NONE = new Anchors(new double[0]);

    /** x, y, z per body. */
    private final double[] xyz;

    private Anchors(double[] xyz) {
        this.xyz = xyz;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return xyz.length == 0;
    }

    public int size() {
        return xyz.length / 3;
    }

    /** Squared distance to the nearest body, as {@code Entity.distanceToSqr}; infinite if none. */
    public double nearestSq(double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < xyz.length; i += 3) {
            double dx = xyz[i] - x;
            double dy = xyz[i + 1] - y;
            double dz = xyz[i + 2] - z;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    /**
     * A body's local cap for a category vanilla caps at {@code max}: its share for the chunks it
     * spawns in, the density a player gets. A player's full cap round 25 chunks let a lone body
     * meet twice the global cap (flown 2026-10-02), since vanilla checks that only once a tick.
     *
     * <p>Animals round up, everything else down: a lone settler's hunting grounds otherwise never
     * refill, as nothing breeds yet (Luiz, 2026-10-03).
     */
    public static int localCap(int max, boolean animals) {
        return share(max, SPAWN_CHUNKS, animals);
    }

    /**
     * The global animal cap over {@code chunks} spawnable chunks while any body anchors, rounded
     * up as {@link #localCap} is: vanilla's rounding down leaves a lone body's 25 chunks none.
     */
    public static int animalGlobalCap(int max, int chunks) {
        return share(max, chunks, true);
    }

    private static int share(int max, int chunks, boolean up) {
        int scaled = max * chunks;
        return up ? (scaled + VANILLA_CAP_CHUNKS - 1) / VANILLA_CAP_CHUNKS : scaled / VANILLA_CAP_CHUNKS;
    }

    /**
     * Calls {@code action} with the index of every body whose spawn chunks hold this one: whose
     * local cap a mob there counts against, and which may let a spawn there.
     */
    public void forEachNear(int chunkX, int chunkZ, IntConsumer action) {
        for (int i = 0; i < xyz.length; i += 3) {
            int bx = (int) Math.floor(xyz[i]) >> 4;
            int bz = (int) Math.floor(xyz[i + 2]) >> 4;
            if (Math.abs(chunkX - bx) <= SPAWN_RADIUS_CHUNKS && Math.abs(chunkZ - bz) <= SPAWN_RADIUS_CHUNKS) {
                action.accept(i / 3);
            }
        }
    }

    /** The chunks the bodies spawn in, each once, as {@code {x, z}} pairs in body order. */
    public List<int[]> spawnChunks() {
        Set<Long> seen = new LinkedHashSet<>();
        for (int i = 0; i < xyz.length; i += 3) {
            int bx = (int) Math.floor(xyz[i]) >> 4;
            int bz = (int) Math.floor(xyz[i + 2]) >> 4;
            for (int dx = -SPAWN_RADIUS_CHUNKS; dx <= SPAWN_RADIUS_CHUNKS; dx++) {
                for (int dz = -SPAWN_RADIUS_CHUNKS; dz <= SPAWN_RADIUS_CHUNKS; dz++) {
                    seen.add(key(bx + dx, bz + dz));
                }
            }
        }
        List<int[]> chunks = new ArrayList<>(seen.size());
        for (long k : seen) {
            chunks.add(new int[] {(int) k, (int) (k >> 32)});
        }
        return chunks;
    }

    private static long key(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static final class Builder {
        private double[] xyz = new double[24];
        private int n;

        private Builder() {}

        public Builder add(double x, double y, double z) {
            if (n + 3 > xyz.length) {
                xyz = Arrays.copyOf(xyz, xyz.length * 2);
            }
            xyz[n++] = x;
            xyz[n++] = y;
            xyz[n++] = z;
            return this;
        }

        public Anchors build() {
            return n == 0 ? NONE : new Anchors(Arrays.copyOf(xyz, n));
        }
    }
}
