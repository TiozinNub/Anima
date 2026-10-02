package dev.luizloyola.anima.compat.terrain;

import dev.luizloyola.anima.core.nav.Surface;
import dev.luizloyola.anima.core.terrain.NaturalGround;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads the natural ground over a box of loaded chunks into a {@link NaturalGround}: each column
 * walked down from its highest block to the first block in {@code #anima:natural_ground}. Never
 * loads a chunk; a column in one that is absent or unprimed stays unknown.
 */
public final class NaturalGroundReader {

    /**
     * Ground nobody put there. {@code data/anima/tags/block/natural_ground.json}; modpacks extend
     * it. Nothing in {@code #anima:used_ground} belongs in it.
     */
    public static final TagKey<Block> NATURAL_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "natural_ground"));

    /** By id: {@code BlockTags.SAPLINGS} is gone from 26.2's API, the tag is not. */
    private static final TagKey<Block> SAPLINGS =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", "saplings"));

    /** A house and its cellar, or the tallest tree, both fit; deeper than this stays unknown. */
    private static final int MAX_DEPTH = 64;

    private NaturalGroundReader() {
    }

    /** The natural ground over {@code [minX, maxX] × [minZ, maxZ]}, inclusive. */
    public static NaturalGround read(Level level, int minX, int minZ, int maxX, int maxZ) {
        NaturalGround ground = new NaturalGround(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) {
                    continue;
                }
                int x0 = Math.max(minX, chunkX << 4);
                int x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4);
                int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        int column = x;
                        int row = z;
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        ground.walk(x, z, top, MAX_DEPTH,
                                y -> cell(chunk.getBlockState(at.set(column, y, row))));
                    }
                }
            }
        }
        return ground;
    }

    /**
     * The feet height over the top natural block of each column of
     * {@code [minX, maxX] × [minZ, maxZ]}, row by row in x — {@link Surface#UNKNOWN} where the chunk
     * is absent or unprimed or nothing natural lies within reach. Walked down from the top that
     * leaves leave out: under a canopy that is the ground itself, one read.
     */
    public static int[] tops(Level level, int minX, int minZ, int maxX, int maxZ) {
        int width = maxX - minX + 1;
        int[] tops = new int[width * (maxZ - minZ + 1)];
        java.util.Arrays.fill(tops, Surface.UNKNOWN);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                int x0 = Math.max(minX, chunkX << 4);
                int x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4);
                int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        tops[(z - minZ) * width + (x - minX)] = top(chunk, at, x, z);
                    }
                }
            }
        }
        return tops;
    }

    /** {@link #tops} for one column of a loaded chunk. */
    public static int top(ChunkAccess chunk, BlockPos.MutableBlockPos at, int x, int z) {
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
            return Surface.UNKNOWN;
        }
        int from = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int ground = NaturalGround.groundOf(from, MAX_DEPTH,
                y -> cell(chunk.getBlockState(at.set(x, y, z))));
        return ground == NaturalGround.UNKNOWN ? Surface.UNKNOWN : ground + 1;
    }

    private static NaturalGround.Cell cell(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return NaturalGround.Cell.FLUID;
        }
        // The plants the home area's clearing takes; a pumpkin or a cactus reads as built.
        if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE)
                || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.FLOWERS)
                || state.is(SAPLINGS)) {
            return NaturalGround.Cell.OPEN;
        }
        if (state.is(GroundReader.NOT_GROUND)) {
            return NaturalGround.Cell.TREE;
        }
        return state.is(NATURAL_GROUND) ? NaturalGround.Cell.NATURAL : NaturalGround.Cell.BUILT;
    }
}
