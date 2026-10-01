package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.agent.Arms;
import dev.luizloyola.anima.core.brain.act.BlockPlacer;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * The {@link BlockPlacer} port over a live {@link AgentBody}, placing through vanilla's own
 * placement — the state a player standing there would get, a door's upper half and a bed's head
 * included — and then setting the {@link Placing#orientation} directly. The item must be carried
 * (one is consumed from the carried inventory, the source of truth the equipment mirror follows),
 * the block must survive there, nothing may stand in it, and it must be within arm's reach of
 * something it can be placed against. Refusal changes nothing.
 */
public final class AgentBlockPlacer implements BlockPlacer {
    /** Arm's reach in blocks (eye to block center) — same as the breaker's. */
    private static final double REACH = 4.5;

    /** What a hand places against, the floor first as a player builds. */
    private static final Direction[] AGAINST = {Direction.DOWN, Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST, Direction.UP};

    private final AgentBody person;

    public AgentBlockPlacer(AgentBody person) {
        this.person = person;
    }

    @Override
    public boolean place(Placing placing) {
        String itemId = placing.itemId();
        if (person.inventory().count(itemId) <= 0) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        if (!placing.block().isEmpty()) {
            Identifier id = Identifier.tryParse(placing.block());
            Optional<Block> named = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
            // The item must be what places it: a torch places a wall torch, a stick never does.
            if (named.isEmpty() || named.get().asItem() != item) {
                return false;
            }
            block = named.get();
        }
        BlockPos pos = new BlockPos(placing.cell().x(), placing.cell().y(), placing.cell().z());
        Level level = person.level();
        LivingEntity body = person.entity();
        if (body.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return false;
        }
        ItemStack stack = new ItemStack(item);
        boolean again = level.getBlockState(pos).is(block);
        BlockPlaceContext context = contextFor(body, level, pos, stack, again);
        if (context == null) {
            return false;
        }
        BlockState state = block.getStateForPlacement(context);
        // Placed again, the cell keeps the way it already faces: a second candle, a double slab.
        if (state != null && !again) {
            state = oriented(state, placing.orientation());
        }
        // Nothing may be built INTO a body — the check a player's own placement always makes. Found
        // in-world on 2026-08-20: a settler put a workbench in the air block Luiz stood in, because
        // the core spot-choosers ask a BlockProbe, which knows only blocks.
        if (state == null || !state.canSurvive(level, pos)
                || !level.isUnobstructed(state, pos, CollisionContext.empty())
                || !bedHeadFits(level, pos, state, context)) {
            return false;
        }
        person.faceBlock(pos);
        level.setBlock(pos, state, Block.UPDATE_ALL_IMMEDIATE);
        BlockState placed = level.getBlockState(pos);
        if (placed.is(block)) {
            block.setPlacedBy(level, pos, placed, body, stack);
            // Vanilla shaped the state to the neighbours for the facing it chose; a stair set to
            // face another way corners again. After setPlacedBy, since a door's lower half alone
            // shapes itself to air.
            placed = level.getBlockState(pos);
            BlockState shaped = Block.updateFromNeighbourShapes(placed, level, pos);
            if (shaped != placed) {
                level.setBlock(pos, shaped, Block.UPDATE_ALL);
                placed = shaped;
            }
        }
        // The world hears it: the vibration bus (sculk, other Persons' ears) and the
        // place-marks that let a WATCHING peer read the swing as building, not mining.
        level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_PLACE, pos,
                net.minecraft.world.level.gameevent.GameEvent.Context.of(body, placed));
        SoundType sound = placed.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        Arms.swingToInteract(body, InteractionHand.MAIN_HAND);
        person.inventory().remove(itemId, 1);
        person.brain().workSpots().record(placing.cell(), level.getGameTime());
        return true;
    }

    /**
     * A click on a face beside the cell, as a hand places a block; none when nothing beside it can
     * be placed against — a block hung in open air is one no player could have put there. A block
     * placed again may be clicked on itself, a top slab from below.
     */
    private static @Nullable BlockPlaceContext contextFor(LivingEntity body, Level level, BlockPos pos,
                                                          ItemStack stack, boolean again) {
        for (Direction side : AGAINST) {
            BlockPos against = pos.relative(side);
            BlockState beside = level.getBlockState(against);
            if (beside.isAir() || beside.canBeReplaced()) {
                continue;
            }
            BlockPlaceContext context = new BodyPlaceContext(body, level, stack, against, side.getOpposite());
            if (context.getClickedPos().equals(pos) && context.canPlace()) {
                return context;
            }
        }
        if (again) {
            for (Direction face : new Direction[]{Direction.UP, Direction.DOWN}) {
                BlockPlaceContext context = new BodyPlaceContext(body, level, stack, pos, face);
                if (context.replacingClickedOnBlock() && context.canPlace()) {
                    return context;
                }
            }
        }
        return null;
    }

    /** Null when a value is not one the block has: a wrong orientation is refused, not guessed. */
    private static @Nullable BlockState oriented(BlockState state, Map<String, String> orientation) {
        for (Map.Entry<String, String> entry : orientation.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            // A door's half is which cell it is, not which way it faces.
            if (property == null || property == BlockStateProperties.DOUBLE_BLOCK_HALF) {
                continue;
            }
            state = with(state, property, entry.getValue());
            if (state == null) {
                return null;
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> @Nullable BlockState with(BlockState state, Property<T> property,
                                                                       String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(null);
    }

    /** Vanilla checked the head's cell for the facing it chose; the facing set may point elsewhere. */
    private static boolean bedHeadFits(Level level, BlockPos pos, BlockState state, BlockPlaceContext context) {
        if (!state.hasProperty(BlockStateProperties.BED_PART)) {
            return true;
        }
        BlockPos head = pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        return level.getBlockState(head).canBeReplaced(context)
                && level.isUnobstructed(state.setValue(BlockStateProperties.BED_PART,
                BedPart.HEAD), head, CollisionContext.empty());
    }

    /**
     * A player's click made by a body that is not a player. Vanilla reads the player's look for the
     * way a block faces, and a null player has none — a torch or a ladder would throw — so the look
     * here is the eye's line to the cell. The facing it gives is only placement's; the orientation
     * set afterwards is what the plan asked for.
     */
    private static final class BodyPlaceContext extends BlockPlaceContext {
        private final Vec3 look;

        BodyPlaceContext(LivingEntity body, Level level, ItemStack stack, BlockPos clicked, Direction face) {
            super(level, null, InteractionHand.MAIN_HAND, stack, new BlockHitResult(
                    Vec3.atCenterOf(clicked).add(face.getStepX() * 0.5, face.getStepY() * 0.5,
                            face.getStepZ() * 0.5), face, clicked, false));
            this.look = Vec3.atCenterOf(clicked.relative(face)).subtract(body.getEyePosition()).normalize();
        }

        @Override
        public Direction getHorizontalDirection() {
            return Direction.fromYRot(getRotation());
        }

        @Override
        public float getRotation() {
            return (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] order = Direction.values().clone();
            Arrays.sort(order, Comparator.comparingDouble(
                    d -> -(d.getStepX() * look.x + d.getStepY() * look.y + d.getStepZ() * look.z)));
            return order;
        }

        @Override
        public Direction getNearestLookingDirection() {
            return getNearestLookingDirections()[0];
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return look.y > 0 ? Direction.UP : Direction.DOWN;
        }
    }
}
