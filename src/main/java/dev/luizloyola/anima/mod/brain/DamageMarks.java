package dev.luizloyola.anima.mod.brain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Who dealt damage lately — the observable half of "attacking": a landed hit is visible and
 * audible, so a short-lived mark on the ATTACKER is fair perception, not telepathy.
 * {@code BeingSense} reads it to split the swinging arm into FIGHTING vs MINING (decision: Luiz).
 * Server-wide and transient.
 *
 * <p>It also routes the other side: being hit is a percept of its own, and the only one that
 * reaches through cover, darkness and a missing line of sight.
 */
public final class DamageMarks {
    private DamageMarks() {}

    /** Attacker uuid → game time of their last landed hit. Pruned opportunistically. */
    private static final Map<UUID, Long> DEALT_AT = new HashMap<>();
    private static final int PRUNE_ABOVE = 256;
    private static final int PRUNE_OLDER_THAN = 1200;

    /** Call once from mod init: marks attackers on every landed hit, clears on server stop. */
    public static void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (source.getEntity() instanceof LivingEntity attacker) {
                long now = entity.level().getGameTime();
                DEALT_AT.put(attacker.getUUID(), now);
                if (DEALT_AT.size() > PRUNE_ABOVE) {
                    DEALT_AT.values().removeIf(at -> now - at > PRUNE_OLDER_THAN);
                }
            }
            // The other half: being hit is itself a percept — the sense is told where it came
            // from and the identification ladder does the rest. Only an ATTACK counts; falling,
            // drowning, starving and fire arrive here too, and routing them would put a hostile
            // track on the victim's own feet. What hurt you needs a face or a direction.
            Vec3 from = source.getSourcePosition() != null
                    ? source.getSourcePosition()
                    : (source.getEntity() != null ? source.getEntity().position() : null);
            if (entity instanceof AgentBody victim && !blocked && from != null) {
                victim.beingSense().attacked(
                        source.getEntity() instanceof LivingEntity living ? living : null, from);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> DEALT_AT.clear());
    }

    public static boolean dealtWithin(UUID who, long now, int ticks) {
        Long at = DEALT_AT.get(who);
        return at != null && now - at <= ticks;
    }
}
