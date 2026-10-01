package dev.luizloyola.anima.core.territory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One chunk of one dimension. The dimension travels with it from the start: a chunk without one is
 * a chunk in the wrong world as soon as anything is claimed outside the overworld.
 *
 * @param dimension the dimension's id, {@code minecraft:overworld}
 */
public record ChunkKey(String dimension, int x, int z) implements Comparable<ChunkKey> {

    public static final String OVERWORLD = "minecraft:overworld";

    private static final Comparator<ChunkKey> ORDER = Comparator.comparing(ChunkKey::dimension)
            .thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z);

    public ChunkKey {
        Objects.requireNonNull(dimension, "dimension");
    }

    /** The chunk holding this block column. */
    public static ChunkKey at(String dimension, int blockX, int blockZ) {
        return new ChunkKey(dimension, blockX >> 4, blockZ >> 4);
    }

    /** Every chunk a block rectangle touches, corners inclusive. */
    public static SortedSet<ChunkKey> covering(String dimension, int minBlockX, int minBlockZ,
                                               int maxBlockX, int maxBlockZ) {
        SortedSet<ChunkKey> chunks = new TreeSet<>();
        for (int x = Math.min(minBlockX, maxBlockX) >> 4; x <= Math.max(minBlockX, maxBlockX) >> 4; x++) {
            for (int z = Math.min(minBlockZ, maxBlockZ) >> 4; z <= Math.max(minBlockZ, maxBlockZ) >> 4; z++) {
                chunks.add(new ChunkKey(dimension, x, z));
            }
        }
        return chunks;
    }

    public int minBlockX() {
        return x << 4;
    }

    public int minBlockZ() {
        return z << 4;
    }

    public int maxBlockX() {
        return (x << 4) + 15;
    }

    public int maxBlockZ() {
        return (z << 4) + 15;
    }

    public ChunkKey offset(int dx, int dz) {
        return new ChunkKey(dimension, x + dx, z + dz);
    }

    /** The four that share an edge with this one — what "one piece" is measured by. */
    public List<ChunkKey> edgeNeighbours() {
        return List.of(offset(1, 0), offset(-1, 0), offset(0, 1), offset(0, -1));
    }

    /** By dimension, then x, then z: what keeps a save and a log line in one order. */
    @Override
    public int compareTo(ChunkKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return "[" + x + ", " + z + "]";
    }
}
