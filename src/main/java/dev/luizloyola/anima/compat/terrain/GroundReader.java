package dev.luizloyola.anima.compat.terrain;

import dev.luizloyola.anima.core.terrain.GroundSample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads the ground over a box of loaded chunks into a {@link GroundSample}: two heightmap lookups
 * and two block reads per column. Never loads a chunk; a column in one that is absent or unprimed
 * stays unknown.
 *
 * <p>{@code MOTION_BLOCKING} counts leaves and {@code MOTION_BLOCKING_NO_LEAVES} does not, so the
 * two disagree exactly where a canopy stands. The surface block's own fluid state is what finds
 * water: under leaves {@code OCEAN_FLOOR} answers with the leaves, and water there would read as
 * land at its surface.
 */
public final class GroundReader {

    /**
     * Blocks that say somebody uses the ground they stand on or in: stations, containers, torches,
     * paths, anything built. {@code data/anima/tags/block/used_ground.json}; modpacks extend it.
     */
    public static final TagKey<Block> USED_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "used_ground"));

    private GroundReader() {
    }

    /** The ground over {@code [minX, maxX] × [minZ, maxZ]}, inclusive. */
    public static GroundSample read(Level level, int minX, int minZ, int maxX, int maxZ) {
        GroundSample sample = new GroundSample(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null
                        || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)
                        || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
                    continue;
                }
                int x0 = Math.max(minX, chunkX << 4);
                int x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4);
                int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                        int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        BlockState ground = chunk.getBlockState(at.set(x, surface, z));
                        // A torch or a rail stops nothing, so it is never the surface: it is the
                        // block on top of it.
                        BlockState above = chunk.getBlockState(at.set(x, surface + 1, z));
                        int flags = 0;
                        if (top > surface) {
                            flags |= GroundSample.CANOPY;
                        }
                        if (!ground.getFluidState().isEmpty()) {
                            flags |= GroundSample.FLUID;
                        }
                        if (ground.is(USED_GROUND) || above.is(USED_GROUND)) {
                            flags |= GroundSample.USED;
                        }
                        sample.set(x, z, surface, flags);
                    }
                }
            }
        }
        return sample;
    }
}
