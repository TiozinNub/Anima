package dev.luizloyola.anima.compat.nav;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * How high and how low the ground goes across a stretch of world, read off the chunks' own
 * heightmaps.
 *
 * <p>A nav capture fixes its vertical extent before reading a block; knowing only the endpoints
 * made the ceiling a fact about them rather than about the terrain between. Two hilltops at 86 and
 * 90 got a ceiling of 96 while the only crossing peaked at 97 (measured in-world 2026-08-14).
 *
 * <p>A heightmap read is a chunk lookup and an array index — no block state, no chunk ever loaded
 * — and the cells it adds are mostly sky, which {@code WorldSnapshot.bake} fills a row at a time.
 */
public final class TerrainProfile {
    private TerrainProfile() {
    }

    /**
     * Clearance kept above the highest ground in the box: enough for a body to stand on top of it
     * and jump. Below it, only enough to classify the floor itself and the cell that holds it.
     */
    private static final int OVER_TERRAIN = 4;
    private static final int UNDER_TERRAIN = 2;

    /**
     * How far past the endpoints' own band the terrain may push the capture: a summit seventy
     * blocks over the two cells being joined is not on the way between them.
     *
     * <p><b>Both numbers are measured</b>, over 900 replayed routes on a real mountainside
     * (heights 66–138):
     *
     * <pre>
     *   above   mean box   routes refused        below   mean box   refused   of which budget
     *       0       34.8         173                 0       40.1      167          28
     *       8       42.8         171                 4       41.0      165          30
     *      16       50.6         171                12       42.2      169          34
     *      48       63.2         171                24       63.2      171          38
     * </pre>
     *
     * <p>The ceiling saturates at 8; lowering the floor sends the search into ground no route
     * needs, spending the node budget and turning working descents into refusals.
     */
    private static final int MAX_ABOVE = 8;
    private static final int MAX_BELOW = 4;

    /** A vertical span of world, inclusive at both ends. */
    public record Band(int low, int high) {
        public Band {
            if (high < low) {
                throw new IllegalArgumentException("high < low: " + low + ".." + high);
            }
        }

        public int span() {
            return this.high - this.low + 1;
        }
    }

    /**
     * The span the ground itself occupies over a horizontal box — the lowest solid floor in it and
     * the highest.
     *
     * <p>Leaves are excluded from the ceiling ({@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES})
     * and fluids from the floor ({@link Heightmap.Types#OCEAN_FLOOR}): a canopy is not ground worth
     * raising a capture for, and a route ending in one keeps its endpoint's cells anyway (see
     * {@link #widen}).
     *
     * <p>{@code null} when not one chunk of the box is loaded. No chunk is ever loaded to answer.
     */
    public static @Nullable Band terrain(Level level, int minX, int minZ, int maxX, int maxZ) {
        int top = Integer.MIN_VALUE;
        int floor = Integer.MAX_VALUE;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null
                        || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)
                        || !chunk.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR)) {
                    continue; // absent or unprimed: the loaded parts still answer
                }
                int x0 = Math.max(minX, chunkX << 4);
                int x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4);
                int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        top = Math.max(top,
                                chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                        floor = Math.min(floor,
                                chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
                    }
                }
            }
        }
        if (top == Integer.MIN_VALUE) {
            return null;
        }
        // An all-air column answers below the world; keep the band well-formed rather than trusting
        // the two heightmaps to agree about a chunk nothing has been generated into.
        return new Band(Math.min(floor, top), Math.max(floor, top));
    }

    /**
     * Grows {@code endpoints} (the band the walk's two ends demand) to hold the ground between
     * them, bounded by {@link #MAX_ABOVE} / {@link #MAX_BELOW}.
     *
     * <p><b>It only ever grows</b>, the endpoint band being what every capture used to be: no walk
     * that worked before can break on a terrain reading that was unavailable, unprimed, or low.
     */
    public static Band widen(Band endpoints, @Nullable Band terrain) {
        if (terrain == null) {
            return endpoints;
        }
        int low = clamp(Math.min(endpoints.low(), terrain.low() - UNDER_TERRAIN),
                endpoints.low() - MAX_BELOW, endpoints.low());
        int high = clamp(Math.max(endpoints.high(), terrain.high() + OVER_TERRAIN),
                endpoints.high(), endpoints.high() + MAX_ABOVE);
        return new Band(low, high);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
