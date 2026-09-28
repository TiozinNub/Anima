package dev.luizloyola.anima.mod.brain;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What an empty hand does to a block, registered by whoever knows the block — Anima knows none.
 * {@link AgentHand} asks each registered use in turn and stops at the first that acts.
 */
public final class BlockUses {

    /** One kind of block, used. */
    @FunctionalInterface
    public interface Use {
        /** Act and return true, or leave the world alone and return false: not this block, or not now. */
        boolean use(ServerLevel level, BlockPos pos, BlockState state, LivingEntity user);
    }

    private static final List<Use> USES = new CopyOnWriteArrayList<>();

    private BlockUses() {
    }

    public static void register(Use use) {
        USES.add(use);
    }

    static boolean use(ServerLevel level, BlockPos pos, BlockState state, LivingEntity user) {
        for (Use use : USES) {
            if (use.use(level, pos, state, user)) {
                return true;
            }
        }
        return false;
    }
}
