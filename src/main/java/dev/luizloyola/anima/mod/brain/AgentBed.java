package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.compat.agent.Sleeping;
import dev.luizloyola.anima.core.brain.act.Bed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** The {@link Bed} port over a live {@link AgentBody}, within a player's reach of a bed. */
public final class AgentBed implements Bed {

    private final AgentBody person;

    public AgentBed(AgentBody person) {
        this.person = person;
    }

    @Override
    public boolean lieDown(Pos cell) {
        if (!(person.level() instanceof ServerLevel level)) {
            return false;
        }
        Optional<BlockPos> head = Sleeping.head(level, new BlockPos(cell.x(), cell.y(), cell.z()));
        if (head.isEmpty()) {
            return false;
        }
        LivingEntity body = person.entity();
        if (body.isSleeping()) {
            return body.getSleepingPos().filter(head.get()::equals).isPresent();
        }
        if (!reaches(body, head.get()) && !reaches(body, Sleeping.foot(level, head.get()))) {
            return false;
        }
        if (Sleeping.occupied(level.getBlockState(head.get()))) {
            return false;
        }
        return Sleeping.lieDown(body, head.get());
    }

    @Override
    public boolean asleep() {
        return person.entity().isSleeping();
    }

    @Override
    public void getUp() {
        if (person.entity().isSleeping()) {
            Sleeping.getUp(person.entity());
        }
    }

    /** {@code ServerPlayer.isReachableBedBlock}'s box. */
    private static boolean reaches(LivingEntity body, BlockPos half) {
        Vec3 bottom = Vec3.atBottomCenterOf(half);
        return Math.abs(body.getX() - bottom.x) <= 3.0
                && Math.abs(body.getY() - bottom.y) <= 2.0
                && Math.abs(body.getZ() - bottom.z) <= 3.0;
    }
}
