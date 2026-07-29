package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.server.MinecraftServer;

/**
 * One body's fears, gathered into a snapshot — the mod-side plumbing behind
 * {@link DangerField#of}. What a body sees now is in its being sense, what it remembers is in the
 * world's knowledge store keyed by its id. One caller hands the result to a worker thread, so the
 * gathering lives in one place that takes it ON the server thread.
 */
public final class DangerFields {

    private DangerFields() {
    }

    /** Everything this body is currently afraid of, seen or remembered. Server thread only. */
    public static DangerField of(AgentBody body) {
        MinecraftServer server = body.level().getServer();
        AgentId self = body.agentId();
        if (server == null || self == null) {
            return DangerField.NONE; // not yet in a world, or not yet anybody
        }
        return DangerField.of(body.danger(), body.beingSense().beings(),
                KnowledgeData.get(server).registry().forPerson(self),
                body.level().getGameTime(), DangerField.FADE_TICKS);
    }
}
