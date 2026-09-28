package dev.luizloyola.anima.compat.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link WorldSnapshot#panelFacing}: a climbing body faces the wall its rungs hang on. */
class ClimbFacingTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static float facing(BlockState state) {
        return WorldSnapshot.panelFacing(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    @Test
    void aLadderIsFacedFromTheFrontIntoItsWall() {
        for (Direction out : Direction.Plane.HORIZONTAL) {
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, out);
            assertEquals(out.getOpposite().toYRot(), facing(ladder), 0.0F, "ladder facing " + out);
        }
    }

    @Test
    void aTrapdoorOpenOverALadderFacesTheSameWall() {
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
        BlockState hatch = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.NORTH)
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, true);
        assertEquals(facing(ladder), facing(hatch), 0.0F);
    }

    @Test
    void aVineOnOneFaceIsFacedAndOnTwoIsNot() {
        BlockState east = Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true);
        assertEquals(Direction.EAST.toYRot(), facing(east), 0.0F);
        assertTrue(Float.isNaN(facing(east.setValue(VineBlock.NORTH, true))));
    }

    @Test
    void scaffoldingHasNoWallToFace() {
        assertTrue(Float.isNaN(facing(Blocks.SCAFFOLDING.defaultBlockState())));
    }
}
