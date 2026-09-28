package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.agent.Arms;
import dev.luizloyola.anima.core.brain.act.Hand;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * The {@link Hand} port over a live {@link AgentBody}: within arm's reach, the first
 * {@link BlockUses} entry that takes the block acts on it, and the arm swings. Nothing registered
 * for the block, or out of reach, changes nothing.
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
        if (!(person.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        if (person.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
            return false;
        }
        person.faceBlock(pos);
        if (!BlockUses.use(level, pos, level.getBlockState(pos), person.entity())) {
            return false;
        }
        Arms.swingToInteract(person.entity(), InteractionHand.MAIN_HAND);
        return true;
    }
}
