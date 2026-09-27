package dev.luizloyola.anima.compat.nav;

import dev.luizloyola.anima.core.nav.NavGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Doors, gates, trapdoors and what opens them, as a body works them — the one place navigation
 * changes the world rather than reading it. Which blocks a hand may swing is the classifier's call
 * ({@code WorldSnapshot}); this only does it.
 */
public final class Doors {
    /**
     * How far from a body standing in front of a door a button or lever may be and still be pressed —
     * about a player's reach from where the eyes are.
     */
    private static final double REACH = 3.0;
    private static final double EYES = 1.6;

    private Doors() {
    }

    /**
     * Swings the door, gate or trapdoor at {@code pos} the other way by hand, as {@code who},
     * answering whether anything moved. A door is swung by its own verb, which plays its sound and
     * moves both halves; a gate and a trapdoor have none but a player's click, so this does what the
     * click does — a gate swinging away from whoever opens it.
     */
    public static boolean swing(LivingEntity who, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(who, level, state, pos, !door.isOpen(state));
            return true;
        }
        if (!state.hasProperty(BlockStateProperties.OPEN)) {
            return false;
        }
        boolean open = !state.getValue(BlockStateProperties.OPEN);
        BlockState next = state.setValue(BlockStateProperties.OPEN, open);
        if (state.getBlock() instanceof FenceGateBlock) {
            Direction heading = who.getDirection();
            if (open && state.getValue(FenceGateBlock.FACING) == heading.getOpposite()) {
                next = next.setValue(FenceGateBlock.FACING, heading);
            }
            set(level, pos, next, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, who);
            return true;
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            set(level, pos, next,
                    open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, who);
            return true;
        }
        return false;
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState next,
                            net.minecraft.sounds.SoundEvent sound, LivingEntity who) {
        boolean open = next.getValue(BlockStateProperties.OPEN);
        level.setBlock(pos, next, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F,
                level.getRandom().nextFloat() * 0.1F + 0.9F);
        level.gameEvent(who, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }

    /**
     * Whether the door, gate or trapdoor at {@code pos} stands open — {@code null} when there is no
     * longer one there to ask.
     */
    public static @Nullable Boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        return (block instanceof DoorBlock || block instanceof FenceGateBlock
                || block instanceof TrapDoorBlock) && state.hasProperty(BlockStateProperties.OPEN)
                ? state.getValue(BlockStateProperties.OPEN)
                : null;
    }

    /**
     * The faces of the door at {@code pos} it can be opened from without a hand on it: a button or
     * lever a body standing outside that face reaches, on that side of the wall, or a pressure plate
     * on the floor there. As {@link NavGrid#heading} bits.
     *
     * <p>Only what powers the door directly counts — the activator beside it, or the block it is
     * fixed to — which is how a door and its button are built. Wiring from further off is not
     * followed, and a door opened only that way reads as shut.
     */
    public static int activatorFaces(Level level, BlockPos pos, BlockState door) {
        int faces = 0;
        for (Direction face : Direction.Plane.HORIZONTAL) {
            if (plateOn(level, pos, door, face) || activatorFor(level, pos, door, face) != null) {
                faces |= NavGrid.heading(face.getStepX(), face.getStepZ());
            }
        }
        return faces;
    }

    /** Whether a pressure plate on the floor outside the door's {@code face} opens it. */
    public static boolean plateOn(Level level, BlockPos pos, BlockState door, Direction face) {
        BlockPos lower = lowerHalf(pos, door);
        BlockPos front = lower.relative(face);
        return level.getBlockState(front).is(BlockTags.PRESSURE_PLATES) && powers(level, front, lower);
    }

    /**
     * The button or lever a body standing outside the door's {@code face} presses to open it — the
     * nearest, or {@code null} when there is none on that side.
     */
    public static @Nullable BlockPos activatorFor(Level level, BlockPos pos, BlockState door,
                                                  Direction face) {
        BlockPos lower = lowerHalf(pos, door);
        Vec3 eyes = Vec3.atBottomCenterOf(lower.relative(face)).add(0.0, EYES, 0.0);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos at : BlockPos.betweenClosed(lower.offset(-2, -1, -2), lower.offset(2, 2, 2))) {
            BlockState state = level.getBlockState(at);
            boolean button = state.is(BlockTags.BUTTONS);
            if (!button && !(state.getBlock() instanceof LeverBlock)) continue;
            // On this face's side of the wall the door stands in, or in its plane: a button fixed to
            // the outside of the wall is not pressed from inside the house.
            int along = face.getAxis().choose(at.getX() - lower.getX(), 0, at.getZ() - lower.getZ());
            int ahead = face.getAxis().choose(face.getStepX(), 0, face.getStepZ());
            if (along * ahead < 0) continue;
            double distance = eyes.distanceTo(Vec3.atCenterOf(at));
            if (distance > REACH || distance >= bestDistance) continue;
            BlockPos support = supportOf(at, state);
            boolean through = level.getBlockState(support).isRedstoneConductor(level, support)
                    && powers(level, support, lower);
            if (!powers(level, at, lower) && !through) continue;
            best = at.immutable();
            bestDistance = distance;
        }
        return best;
    }

    /**
     * Presses the button or pulls the lever at {@code pos}, as {@code who}, answering whether it did.
     * A button already down is left alone: pressing it again would not keep the door open longer.
     */
    public static boolean press(LivingEntity who, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ButtonBlock button) {
            if (state.getValue(BlockStateProperties.POWERED)) return false;
            button.press(state, level, pos, null);
            return true;
        }
        if (state.getBlock() instanceof LeverBlock lever) {
            lever.pull(state, level, pos, null);
            return true;
        }
        return false;
    }

    /** Whether the lever at {@code pos} is on — {@code null} if it is not a lever. */
    public static @Nullable Boolean leverOn(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof LeverBlock ? state.getValue(BlockStateProperties.POWERED) : null;
    }

    private static BlockPos lowerHalf(BlockPos pos, BlockState door) {
        return door.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && door.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? pos.below() : pos;
    }

    /** Whether power at {@code source} reaches the door whose lower half is at {@code lower}: beside either half. */
    private static boolean powers(Level level, BlockPos source, BlockPos lower) {
        return source.distManhattan(lower) == 1 || source.distManhattan(lower.above()) == 1;
    }

    /** The block a button or lever is fixed to, which it powers through when that block is solid. */
    private static BlockPos supportOf(BlockPos at, BlockState state) {
        AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
        return switch (face) {
            case FLOOR -> at.below();
            case CEILING -> at.above();
            case WALL -> at.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        };
    }
}
