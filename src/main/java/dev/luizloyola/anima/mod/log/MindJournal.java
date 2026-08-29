package dev.luizloyola.anima.mod.log;

import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

/**
 * Hangs a {@code Category.MIND} line off every body's own need-level crossings. {@code core}
 * cannot reach a journal ({@link dev.luizloyola.anima.core.agent.need.Needs#onCrossing}), so this
 * is the mod-layer listener that seam was built for — installed at {@code ENTITY_LOAD}, the same
 * moment {@link dev.luizloyola.anima.mod.body.AgentBodies} starts indexing the body, so a fresh
 * {@code Needs} (spawned or loaded from NBT) never ticks unwatched.
 */
public final class MindJournal {
    private MindJournal() {
    }

    /** Call once from mod init. */
    public static void install() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AgentBody body) {
                body.needs().onCrossing(crossing -> body.journal().record(Category.MIND,
                        crossing.kind().key(), crossing.from().key() + " -> " + crossing.to().key()
                                + String.format(Locale.ROOT, " (pressure %.2f)",
                                        crossing.pressure())));
            }
        });
    }
}
