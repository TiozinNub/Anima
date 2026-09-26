package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.mod.body.AgentBody;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.tags.DamageTypeTags;

/**
 * Tells a body's legs when a fall hurt it, so the move it was making is not planned again
 * ({@link Navigator#hurtByFall}). Anima's by the wolf rule: a pet can fall off a ledge too.
 */
public final class Falls {

    private Falls() {
    }

    /** Call once from mod init. */
    public static void init() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (entity instanceof AgentBody body && !blocked && damageTaken > 0
                    && source.is(DamageTypeTags.IS_FALL)) {
                body.navigator().hurtByFall();
            }
        });
    }
}
