package dev.luizloyola.anima.compat.agent;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * A body in a bed, through vanilla's own {@code startSleeping}: the pose, the body on the bed and the
 * bed marked occupied, as for a villager. 26.3 made it answer whether it worked; older nodes lie
 * the body down on any block, so callers check the bed first.
 *
 * <p>A bed is {@code #minecraft:beds}, which leaves out 26.3's straw bed: it can break when its
 * sleeper gets up, so it is nobody's bed of their own.
 */
public final class Sleeping {

    private Sleeping() {
    }

    public static boolean isBed(BlockState state) {
        return state.is(BlockTags.BEDS);
    }

    /** The head of the bed one of whose halves is at {@code pos}, where a sleeper's pos is kept. */
    public static Optional<BlockPos> head(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isBed(state)) {
            return Optional.empty();
        }
        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return Optional.of(pos);
        }
        BlockPos head = pos.relative(state.getValue(BedBlock.FACING));
        return isBed(level.getBlockState(head)) ? Optional.of(head) : Optional.empty();
    }

    /** The foot of the bed whose head is at {@code head}. */
    public static BlockPos foot(BlockGetter level, BlockPos head) {
        return head.relative(level.getBlockState(head).getValue(BedBlock.FACING).getOpposite());
    }

    public static boolean occupied(BlockState bed) {
        return bed.getValue(BedBlock.OCCUPIED);
    }

    /** Lie {@code body} down in the bed whose head is {@code head}; true when it is asleep there. */
    public static boolean lieDown(LivingEntity body, BlockPos head) {
        //? if >=26.3 {
        /*return body.startSleeping(head);
        *///?} else {
        body.startSleeping(head);
        return body.isSleeping();
        //?}
    }

    public static void getUp(LivingEntity body) {
        body.stopSleeping();
    }
}
