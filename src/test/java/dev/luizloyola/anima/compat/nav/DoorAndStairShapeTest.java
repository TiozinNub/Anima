package dev.luizloyola.anima.compat.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.nav.Doorway;
import dev.luizloyola.anima.core.nav.NavGrid;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The geometry under a {@code DOOR}'s passages and a stair's ramps, over real blockstates. Which
 * blocks are asked is decided by vanilla tags, which are empty until a datapack loads, so that half
 * is the gauntlet capture's to check; this is the half a drawn map would only assume.
 */
class DoorAndStairShapeTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VoxelShape shape(BlockState state) {
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static final int ALL = NavGrid.NORTH | NavGrid.SOUTH | NavGrid.WEST | NavGrid.EAST;

    /** The code of a block a hand swings: as it stands, and swung the other way. */
    private static int byHand(BlockState state) {
        return WorldSnapshot.doorCode(shape(state), shape(state.cycle(BlockStateProperties.OPEN)), true);
    }

    private static BlockState door(Direction facing, boolean open, DoorHingeSide hinge) {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HINGE, hinge);
    }

    @Test
    void aShutDoorsPanelIsOnOneFaceAndSwingsToTheNext() {
        // Facing north and shut: the panel lies on the south face. Swung open on a left hinge it
        // turns clockwise, to the west face.
        assertEquals(Doorway.of(NavGrid.SOUTH, false, NavGrid.WEST, false, ALL, true),
                byHand(door(Direction.NORTH, false, DoorHingeSide.LEFT)));
    }

    @Test
    void anOpenDoorsPanelIsOnTheFaceItsHingeTurnsItTo() {
        assertEquals(Doorway.of(NavGrid.WEST, false, NavGrid.SOUTH, false, ALL, true),
                byHand(door(Direction.NORTH, true, DoorHingeSide.LEFT)));
        assertEquals(Doorway.of(NavGrid.EAST, false, NavGrid.SOUTH, false, ALL, true),
                byHand(door(Direction.NORTH, true, DoorHingeSide.RIGHT)));
        assertEquals(Doorway.of(NavGrid.NORTH, false, NavGrid.WEST, false, ALL, true),
                byHand(door(Direction.EAST, true, DoorHingeSide.LEFT)));
    }

    @Test
    void anIronDoorSwingsOnlyForWhatPowersIt() {
        // No hand swings it, so nobody can until a button or a plate is found beside it — which is a
        // question about where it stands, and not asked here.
        BlockState shut = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        int code = WorldSnapshot.doorCode(shape(shut), shape(shut.cycle(BlockStateProperties.OPEN)), false);
        assertEquals(Doorway.of(NavGrid.SOUTH, false, NavGrid.WEST, false, 0, false), code);
        assertEquals(false, Doorway.swings(code));
    }

    @Test
    void aShutGatesBarCrossesTheMiddleAndAnOpenOneIsGone() {
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.NORTH)
                .setValue(FenceGateBlock.OPEN, false);
        assertEquals(Doorway.of(NavGrid.WEST | NavGrid.EAST, true, 0, false, ALL, true), byHand(gate));
    }

    @Test
    void anOpenTrapdoorOnEdgeIsAPanelOnOneFace() {
        VoxelShape open = shape(Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.NORTH)
                .setValue(TrapDoorBlock.HALF, Half.BOTTOM)
                .setValue(TrapDoorBlock.OPEN, true));
        assertEquals(Doorway.of(NavGrid.SOUTH, false, NavGrid.SOUTH, false, 0, false),
                WorldSnapshot.doorCode(open, open, false));
    }

    private static int ramps(Direction facing, Half half, StairsShape shape) {
        return WorldSnapshot.rampsOf(shape(Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, half)
                .setValue(StairBlock.SHAPE, shape)));
    }

    @Test
    void aStraightStairIsARampTheWayItFaces() {
        assertEquals(NavGrid.EAST, ramps(Direction.EAST, Half.BOTTOM, StairsShape.STRAIGHT));
        assertEquals(NavGrid.NORTH, ramps(Direction.NORTH, Half.BOTTOM, StairsShape.STRAIGHT));
    }

    @Test
    void anOuterCornerIsARampTwoWays() {
        assertEquals(NavGrid.NORTH | NavGrid.WEST,
                ramps(Direction.NORTH, Half.BOTTOM, StairsShape.OUTER_LEFT));
        assertEquals(NavGrid.NORTH | NavGrid.EAST,
                ramps(Direction.NORTH, Half.BOTTOM, StairsShape.OUTER_RIGHT));
    }

    @Test
    void anInnerCornerAnUpsideDownStairAndAFullBlockAreNoRamp() {
        assertEquals(0, ramps(Direction.NORTH, Half.BOTTOM, StairsShape.INNER_LEFT));
        assertEquals(0, ramps(Direction.NORTH, Half.TOP, StairsShape.STRAIGHT));
        assertEquals(0, WorldSnapshot.rampsOf(shape(Blocks.STONE.defaultBlockState())));
    }
}
