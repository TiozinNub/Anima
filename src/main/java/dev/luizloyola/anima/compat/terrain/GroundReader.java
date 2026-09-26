package dev.luizloyola.anima.compat.terrain;

import dev.luizloyola.anima.core.terrain.FrozenWater;
import dev.luizloyola.anima.core.terrain.GroundSample;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * Reads the ground over a box of loaded chunks into a {@link GroundSample}: two heightmap lookups
 * and two block reads per column, and a read down to the ground where a trunk or a huge mushroom
 * stands on it. Never loads a chunk; a column in one that is absent or unprimed stays unknown.
 *
 * <p>{@code MOTION_BLOCKING} counts leaves and {@code MOTION_BLOCKING_NO_LEAVES} does not, so the
 * two disagree exactly where a canopy stands. The surface block's own fluid state is what finds
 * water: under leaves {@code OCEAN_FLOOR} answers with the leaves, and water there would read as
 * land at its surface. Ice has none, and no heightmap sees under it, so a column topped with ice or
 * snow is walked down ({@link FrozenWater}).
 */
public final class GroundReader {

    /**
     * Blocks that say somebody uses the ground they stand on or in: stations, containers, torches,
     * paths, anything built. {@code data/anima/tags/block/used_ground.json}; modpacks extend it.
     */
    public static final TagKey<Block> USED_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "used_ground"));

    /**
     * Blocks that stand on the ground without being it: logs and huge mushrooms, read under.
     * {@code data/anima/tags/block/not_ground.json}. A mushroom cap is 5–7 wide, too wide for
     * {@code Terrain}'s opening, and read as ground it rings every huge mushroom with a cliff.
     */
    public static final TagKey<Block> NOT_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "not_ground"));

    /** The heightmap's own test, so the ground under a tree is where it would stop without one. */
    private static final Predicate<BlockState> SURFACE =
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();

    /** Past the tallest vanilla tree; a column with no ground within it keeps what it showed. */
    private static final int MAX_GROWTH = 48;

    /** Growth this tall stands on the ground. One log lying on it is a fallen tree. */
    private static final int STANDING_HEIGHT = 2;

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
                        int flags = 0;
                        if (top > surface) {
                            flags |= GroundSample.CANOPY;
                        }
                        if (ground.is(NOT_GROUND)) {
                            int under = groundUnder(chunk, at, x, surface, z);
                            if (surface - under >= STANDING_HEIGHT) {
                                flags |= GroundSample.STANDING;
                            }
                            surface = under;
                            ground = chunk.getBlockState(at.set(x, surface, z));
                        }
                        // A torch or a rail stops nothing, so it is never the surface: it is the
                        // block on top of it.
                        BlockState above = chunk.getBlockState(at.set(x, surface + 1, z));
                        FluidState fluid = ground.getFluidState();
                        if (!fluid.isEmpty()) {
                            flags |= GroundSample.FLUID;
                            if (fluid.is(FluidTags.LAVA)) {
                                flags |= GroundSample.LAVA;
                            }
                        } else if (ground.is(BlockTags.ICE) || ground.is(BlockTags.SNOW)) {
                            int column = x;
                            int row = z;
                            int water = FrozenWater.surface(y -> frozenCell(chunk, at, column, y, row),
                                    surface, level.getSeaLevel(), MAX_GROWTH);
                            if (water != FrozenWater.LAND) {
                                flags |= GroundSample.FLUID;
                                surface = water;
                            }
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

    /** A cell as {@link FrozenWater} reads it, by vanilla tag so modded ice and snow count too. */
    private static FrozenWater.Cell frozenCell(ChunkAccess chunk, BlockPos.MutableBlockPos at, int x,
                                               int y, int z) {
        BlockState state = chunk.getBlockState(at.set(x, y, z));
        if (state.is(BlockTags.ICE)) {
            return FrozenWater.Cell.ICE;
        }
        if (state.is(BlockTags.SNOW)) {
            return FrozenWater.Cell.SNOW;
        }
        return state.getFluidState().is(FluidTags.WATER) ? FrozenWater.Cell.WATER
                : FrozenWater.Cell.OTHER;
    }

    /** The first block below {@code from} that is ground, or {@code from} when none is near. */
    private static int groundUnder(ChunkAccess chunk, BlockPos.MutableBlockPos at, int x, int from,
                                   int z) {
        for (int y = from - 1; y >= from - MAX_GROWTH; y--) {
            BlockState state = chunk.getBlockState(at.set(x, y, z));
            if (SURFACE.test(state) && !state.is(NOT_GROUND)) {
                return y;
            }
        }
        return from;
    }
}
