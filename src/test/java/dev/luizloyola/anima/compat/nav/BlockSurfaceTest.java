package dev.luizloyola.anima.compat.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.nav.CellType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What {@code WorldSnapshot} makes of real blockstates — the half of navigation a drawn map cannot
 * test, since drawing a slab as a slab assumes the answer. Every {@link CellType#STEP} below read
 * as {@link CellType#OBSTACLE} before a floor could stop inside its own cell: village streets,
 * wheat fields and snowy hillsides were terrain to route around.
 *
 * <p>{@code Bootstrap.bootStrap()} builds every non-dynamic collision shape, which is all the
 * classifier reads, so this runs headless. Tag-driven branches (fire, campfires, leaves) are
 * left out (tags are empty until a datapack load), and stay the gauntlet's job.
 */
class BlockSurfaceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CellType typeOf(BlockState state) {
        return WorldSnapshot.type(WorldSnapshot.classifyLive(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    /** How the classifier reads one blockstate: how far into its cell the feet would rest. */
    private static double surfaceOf(BlockState state) {
        return WorldSnapshot.surface(WorldSnapshot.classifyLive(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    private static void assertStep(BlockState state, double surface, String what) {
        assertEquals(CellType.STEP, typeOf(state), what + " is a floor inside its own cell");
        assertEquals(surface, surfaceOf(state), 1.0e-9, what + " surface height");
    }

    @Test
    void aBottomSlabIsHalfABlockOfFloor() {
        assertStep(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM), 0.5, "a bottom slab");
    }

    @Test
    void aTopSlabFillsItsCellAndIsOrdinaryGround() {
        // A top slab's surface is the top of the cell, so the body stands in the cell above as it
        // does on stone. Here to show the split is at the right place, not to test the slab.
        assertEquals(CellType.GROUND, typeOf(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP)));
    }

    @Test
    void aStairBlockIsStoodOnAtItsUpperTread() {
        // A body's footprint in the middle of the cell overlaps the raised half, so it rests at the
        // top — which is why a staircase climbs as full-block steps and not as half ones.
        assertEquals(CellType.GROUND, typeOf(Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.HALF, Half.BOTTOM)));
    }

    @Test
    void aDirtPathIsFifteenSixteenths() {
        assertStep(Blocks.DIRT_PATH.defaultBlockState(), 0.9375, "a dirt path");
    }

    @Test
    void farmlandIsFifteenSixteenthsToo() {
        assertStep(Blocks.FARMLAND.defaultBlockState(), 0.9375, "farmland");
    }

    @Test
    void aCarpetIsOneSixteenth() {
        assertStep(Blocks.WHITE_CARPET.defaultBlockState(), 0.0625, "a carpet");
    }

    @Test
    void snowLayersAreExactlyAsDeepAsTheyCollide() {
        // Snow collides two sixteenths lower than it is drawn, and the first layer does not collide
        // at all — you walk over a dusting without stepping up. Measured from the block, never the
        // drawn height, which was wrong about every layer.
        assertEquals(CellType.PASSABLE,
                typeOf(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 1)),
                "one layer is a dusting with no collision at all");
        // Eight layers LOOKS like a full block but collides at fourteen sixteenths, so a
        // player sinks slightly into deep snow.
        for (int layers = 2; layers <= 8; layers++) {
            assertStep(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, layers),
                    (layers - 1) * 2 / 16.0, "snow of " + layers + " layers");
        }
    }

    @Test
    void aClosedBottomTrapdoorIsAThinFloor() {
        assertStep(Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.OPEN, false), 0.1875, "a closed bottom trapdoor");
    }

    @Test
    void aFenceStillReachesPastItsCellAndStaysAWall() {
        // The check that keeps this from being a blanket "everything is walkable": a fence post is
        // 1.5 tall, so nothing stands inside its cell, and it must still be routed around.
        assertEquals(CellType.OBSTACLE, typeOf(Blocks.OAK_FENCE.defaultBlockState()));
        assertEquals(CellType.OBSTACLE, typeOf(Blocks.COBBLESTONE_WALL.defaultBlockState()));
    }

    @Test
    void wallHuggingCollisionIsNoFootingAndStaysAnObstacle() {
        // A ladder and a CLOSED door have the same collision: a thin panel against one side of the
        // cell, which a centred body never touches, so the probe finds no footing. Whether a body
        // may cross is a per-direction question this vocabulary cannot ask, so both stay OBSTACLE.
        assertEquals(CellType.OBSTACLE, typeOf(Blocks.LADDER.defaultBlockState()));
        assertEquals(CellType.OBSTACLE, typeOf(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, false)));
        assertEquals(CellType.OBSTACLE, typeOf(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, true)),
                "an open door is walkable in fact, and refusing it is the known limit of a "
                        + "per-cell reading rather than an accident");
    }

    @Test
    void plainStoneIsStillPlainGround() {
        assertEquals(CellType.GROUND, typeOf(Blocks.STONE.defaultBlockState()));
        assertEquals(CellType.PASSABLE, typeOf(Blocks.AIR.defaultBlockState()));
    }
}
