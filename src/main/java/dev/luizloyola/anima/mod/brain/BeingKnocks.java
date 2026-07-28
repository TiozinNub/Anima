package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The knock: mining-in-progress delivered to nearby ears (see {@code ServerLevelCrackMixin} for why
 * the crack broadcast is the sound). Knocks are LOUD — they carry regardless of the view cone,
 * walls, or a CROUCHING miner. Once per crack stage, so a bare-hand break knocks ~10 times, inside
 * the ear's freshness window. Person-only: mobs do not mine (door-banging rides ENTITY_DAMAGE).
 */
public final class BeingKnocks {
    private BeingKnocks() {}

    public static void onCrack(ServerLevel level, int breakerId, BlockPos pos) {
        Entity breaker = level.getEntity(breakerId);
        if (!(breaker instanceof LivingEntity body) || !body.isAlive()) {
            return;
        }
        boolean personShaped = body instanceof AgentBody
                || (body instanceof Player player && !player.isSpectator());
        if (!personShaped) {
            return;
        }
        int radius = Config.get().i(Knob.PEERS_HEARING_RADIUS);
        if (radius <= 0) {
            return;
        }
        Vec3 at = Vec3.atCenterOf(pos);
        // Ears belong to the living: a killed AgentBody lingers as a corpse and heard() writes
        // into its sense table synchronously, so without this filter a corpse accrues beings
        // nothing will read. Same guard the aggro mixin applies.
        // Queried as LivingEntity and filtered: the body contract is an interface a consumer's
        // entity implements, not an entity class, so vanilla's type-indexed lookup cannot see it.
        for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(at, radius * 2.0, radius * 2.0, radius * 2.0),
                EntitySelector.LIVING_ENTITY_STILL_ALIVE)) {
            if (!(nearby instanceof AgentBody listener)) {
                continue;
            }
            if (listener != body && at.distanceTo(listener.entity().getEyePosition()) <= radius) {
                listener.beingSense().heard(body, Being.Activity.MINING,
                        Being.Locomotion.STILL, false);
            }
        }
    }
}
