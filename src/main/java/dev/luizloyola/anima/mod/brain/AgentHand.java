package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.agent.Arms;
import dev.luizloyola.anima.core.brain.act.Hand;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.body.AgentBody;
import dev.luizloyola.anima.mod.nav.Doorways;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;

/**
 * The {@link Hand} port over a live {@link AgentBody}: within arm's reach, a click on the block —
 * first any {@link BlockUses} entry that takes it (a consumer's own meaning, a berry bush picked into
 * the pack), then vanilla's own click ({@link BodyClick}), and the arm swings. Out of reach, or a
 * click that does nothing, changes nothing. Doors are shut through {@link Doorways}.
 */
public final class AgentHand implements Hand {
    /** Arm's reach in blocks (eye to block center) — the placer's and the breaker's. */
    private static final double REACH = 4.5;

    private final AgentBody person;

    public AgentHand(AgentBody person) {
        this.person = person;
    }

    @Override
    public boolean use(Pos cell) {
        return click(cell, null);
    }

    /**
     * A click with the cell's own block places it again where vanilla counts it — a fourth snow
     * layer, a third candle; any other item is vanilla's click holding it.
     */
    @Override
    public boolean use(String itemId, Pos cell) {
        Identifier id = Identifier.tryParse(itemId);
        if (id != null && BuiltInRegistries.ITEM.getValue(id) instanceof BlockItem item
                && person.level().getBlockState(new BlockPos(cell.x(), cell.y(), cell.z())).is(item.getBlock())
                && new AgentBlockPlacer(person).place(Placing.of(itemId, cell))) {
            return true;
        }
        return click(cell, itemId);
    }

    private boolean click(Pos cell, String itemId) {
        if (!(person.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        if (person.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return false;
        }
        person.faceBlock(pos);
        boolean changed = (itemId == null && BlockUses.use(level, pos, level.getBlockState(pos), person.entity()))
                || BodyClick.click(person, level, pos, itemId);
        if (!changed) {
            return false;
        }
        Arms.swingToInteract(person.entity(), InteractionHand.MAIN_HAND);
        person.brain().workSpots().record(cell, level.getGameTime());
        return true;
    }

    @Override
    public boolean shut(Pos door) {
        if (!(person.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = new BlockPos(door.x(), door.y(), door.z());
        if (!level.isLoaded(pos)
                || person.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return false;
        }
        return Doorways.shut(person, level, pos);
    }
}
